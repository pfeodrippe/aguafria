(ns learn.jvm-audit-classification
  "Combine reviewed, case-addressed findings without silently classifying failures."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [learn.jvm-audit-runner :as runner]))

(def reviewed-statuses #{:failed :worker-exited :blocked-by-load})
(def categories
  #{:expected-source-failure :invalid-probe-context :state-replay-artifact
    :cascading-failure :jvm-interop-defect :native-safety-defect :target-limitation})

(def family-names
  "Canonical report labels for independently reviewed descriptions of the same
  observable gap. The original reviewer label remains in :reviewed-family."
  {:enum-value-type-loss :enum-type-preservation
   :enum-type-erased-to-string :enum-type-preservation
   :builtin-result-type :builtin-result-type-bridge
   :builtin-anytype-return-signature :builtin-result-type-bridge
   :result-type-builtin-adapter :builtin-result-type-bridge
   :contextual-result-type-bridge :builtin-result-type-bridge
   :clojure-try-does-not-unwrap-error-union :error-union-jvm-unwrapping
   :error-union-unwrapping :error-union-jvm-unwrapping
   :native-control-not-jvm-callable :native-control-jvm-expansion
   :native-capture-binding-not-jvm-callable :native-control-jvm-expansion
   :native-label-binding-not-jvm-callable :native-control-jvm-expansion
   :sentinel-string-pointer-marshalling :sentinel-slice-transport
   :binary-slice-source-escaping :byte-slice-string-escaping
   :c-import-jvm-expansion :c-import-jvm-scope
   :comptime-type-info-transport :comptime-typeinfo-marshalling
   :authored-unqualified-native-syntax :unqualified-native-syntax
   :local-type-function-resolution :type-expression-name-resolution
   :variadic-extern-arity :variadic-call-arity
   :mutable-address-constness-lost :mutable-pointer-qualifier
   :clojure-deref-native-string-mismatch :string-pointer-dereference})

(defn read-edn [path]
  (edn/read-string (slurp path)))

(defn- indexed-cases [report]
  (for [e (:examples report), [i c] (map-indexed vector (:cases e))]
    (assoc c :zig (:zig e) :index i :source (:source e)
           :evidence-path (:evidence-path e))))

(defn- disposition [probe]
  (case (:status probe)
    :passed
    {:category :passed-probe :family :evaluation-and-printing
     :reason "Evaluation/printing completed without throwing. Authored assertions ran where present; Var inspection alone does not establish callable behavior or semantic equivalence."}
    :requires-arguments
    {:category :unexecuted-function-context :family :arguments-not-supplied
     :reason "This inventoried function-body expression requires its function arguments. No arbitrary inputs were invented; a passing authored invocation elsewhere does not independently verify every argument/path."}
    :requires-control-context
    {:category :unexecuted-control-context :family :enclosing-control-required
     :reason "The planner retained this branch/binding/control-dependent expression as syntax rather than evaluating it outside its enclosing control form. It is not a passed probe; inspect the exact form/path in the evidence."}
    :requires-declaration-context
    {:category :unexecuted-declaration-context :family :enclosing-declaration-required
     :reason "This is nested type/member/extern declaration syntax requiring its enclosing declaration, not an independently callable JVM expression. No execution is claimed."}
    (throw (ex-info "Case lacks a reviewed classification" (select-keys probe [:zig :index :status])))))

(defn combine
  "Reject missing/duplicate/out-of-range reviews instead of assigning an unknown
  or catch-all defect category. Original statuses and evidence remain intact."
  [report shards]
  (let [cases (vec (indexed-cases report))
        key-of (juxt :zig :index)
        required (set (map key-of (filter #(reviewed-statuses (:status %)) cases)))
        reviews (vec (mapcat :cases shards))
        keys (mapv key-of reviews)
        supplied (set keys)
        missing (remove supplied required)
        extra (remove required supplied)]
    (when (or (seq missing) (seq extra) (not= (count keys) (count supplied)))
      (throw (ex-info "Classification coverage mismatch"
                      {:missing (vec missing) :extra (vec extra)
                       :duplicates (into {} (filter #(> (val %) 1) (frequencies keys)))})))
    (doseq [r reviews]
      (when-not (and (categories (:category r))
                     (keyword? (:family r))
                     (string? (:reason r))
                     (not (str/blank? (:reason r)))
                     (seq (:evidence r)))
        (throw (ex-info "Incomplete reviewed finding" r))))
    (let [by-id (into {} (map (juxt key-of identity) reviews))
          classified (mapv (fn [c]
                             (let [r (or (get by-id (key-of c)) (disposition c))]
                               (assoc c :classification
                                      (assoc r :reviewed-family (:family r)
                                               :family (get family-names (:family r) (:family r)))))) cases)]
      {:files (count (:examples report))
       :cases classified
       :file-reviews (vec (mapcat :files shards))
       :rechecks (vec (mapcat :rechecks shards))
       :summary (frequencies (map (comp :category :classification) classified))
       :failure-summary (frequencies
                         (map (comp :category :classification)
                              (filter #(#{:failed :worker-exited} (:status %)) classified)))
       :load-blocked-summary (frequencies
                              (map (comp :category :classification)
                                   (filter #(= :blocked-by-load (:status %)) classified)))})))

(defn- escaped [value]
  (-> (str value) (str/replace "|" "\\|") (str/replace #"\s+" " ")))

(defn- link [label path]
  (str "[" label "](<" (.getCanonicalPath (io/file path)) ">)"))

(defn- ranges [indices]
  (->> (sort indices)
       (reduce (fn [groups i]
                 (if (= i (some-> groups peek peek inc))
                   (update groups (dec (count groups)) conj i)
                   (conj groups [i]))) [])
       (map (fn [xs] (if (= 1 (count xs)) (str (first xs)) (str (first xs) "–" (last xs)))))
       (str/join ", ")))

(defn- counts-table [counts]
  (str "| Classification | Cases |\n|---|---:|\n"
       (apply str (for [[k n] (sort-by (comp str key) counts)]
                    (str "| `" (name k) "` | " n " |\n")))))

(defn- file-section [[zig cases]]
  (let [first-case (first cases)
        groups (group-by #(select-keys (:classification %) [:category :family :reason :origin]) cases)]
    (str "### " (escaped zig) "\n\n"
         (link "Clojure source" (io/file "resources" (:source first-case))) " · "
         (link "Original Zig" (io/file "resources/upstream/doc/langref" zig)) " · "
         (link "Exact forms, output and diagnostics" (:evidence-path first-case)) "\n\n"
         "Case indices are zero-based within this file's reviewed case vector. "
         "Each case is listed once below; ranges include both endpoints.\n\n"
         "| Case IDs | Classification / family | Evidence-based explanation |\n|---|---|---|\n"
         (apply str
                (for [[finding rows] (sort-by #(apply min (map :index (val %))) groups)]
                  (str "| " (ranges (map :index rows)) " | `" (name (:category finding))
                       "` / `" (name (:family finding)) "` | " (escaped (:reason finding))
                       (when (some? (:origin finding))
                         (str " Origin: " (:origin finding) ".")) " |\n")))
         "\n")))

(defn- defect-families [cases]
  (let [defects (filter #(#{:jvm-interop-defect :native-safety-defect}
                           (get-in % [:classification :category])) cases)]
    (str "## Defect families requiring fixes\n\n"
         "These are observed JVM incompatibilities/safety failures, grouped by symptom "
         "and source evidence. They are not a count of independent compiler root causes; "
         "multiple families can share an implementation cause. Load-blocked rows inherit "
         "their namespace's cause and do not represent independently executed failures.\n\n"
         "| Family | Failed/exited probes | Load-blocked cases | Files |\n|---|---:|---:|---|\n"
         (apply str
                (for [[family rows] (sort-by (comp str key)
                                            (group-by #(get-in % [:classification :family]) defects))]
                  (str "| `" (name family) "` | "
                       (count (filter #(#{:failed :worker-exited} (:status %)) rows)) " | "
                       (count (filter #(= :blocked-by-load (:status %)) rows)) " | "
                       (escaped (str/join ", " (sort (set (map :zig rows))))) " |\n")))
         "\n")))

(defn markdown [result]
  (str "# Classified Learn JVM audit — 2026-09-25\n\n"
       "## What is complete, and what is not\n\n"
       "All **" (:files result) " files**, **" (count (:cases result)) " inventoried cases**, "
       "**1,208 failed probes**, **three worker-exit probes**, and **866 load/target-blocked cases** "
       "now have case-addressed classifications. There is no unclassified failure bucket. "
       "Classification is based on the actual form, diagnostic, retained lexical prefix "
       "and original Zig source—not merely the filename or expected-error flag.\n\n"
       "**This finishes classification of the recorded run, not compiler fixes or complete "
       "execution coverage.** 4,796 function/control/declaration-context cases were not "
       "independently executed. Their reason and identity are recorded below; they are not "
       "passes. Together with 866 load/target-blocked cases, 5,662 remain unexecuted. "
       "A passing authored main/test does not make all its detached subexpressions callable "
       "from ordinary Clojure. Conversely, branch captures, return statements and inferred "
       "result types cannot meaningfully be evaluated without their required context.\n\n"
       "The original four-worker run and raw errors remain unchanged. Reviewed cases are "
       "identified against the corrected 9,588-case aggregate; expected native failures "
       "remain errors, not rewritten passes. Reproduction checks use the same public JVM "
       "API as an ordinary REPL. No compiler or authored-example fixes were made for this report.\n\n"
       "Every recorded failure was reviewed against its form and source. Selected ambiguous "
       "families received additional isolated REPL checks; not every recorded failure was "
       "rerun independently. Category counts are probe counts, not counts of unique bugs.\n\n"
       "## Classification of the 1,211 failed/interrupted probes\n\n"
       (counts-table (:failure-summary result))
       "\n`jvm-interop-defect` includes missing JVM API behavior and example-level JVM "
       "portability gaps; the family/reason distinguishes these. `cascading-failure` is "
       "not an additional defect: the retained prefix or earlier generated adapter fails "
       "before that probe's target is reached. `state-replay-artifact` means ordered audit "
       "replay violated a fixture's initial-state assumption. `invalid-probe-context` "
       "includes missing authored build/link/import setup; these remain actionable audit/" 
       "Learn integration issues, not verified compiler failures.\n\n"
       "## Classification of 866 load/target-blocked cases\n\n"
       (counts-table (:load-blocked-summary result))
       "\n## Complete inventory\n\n" (counts-table (:summary result)) "\n"
       (defect-families (:cases result))
       "## File-by-file reviewed ledger\n\n"
       (apply str (map file-section (sort-by key (group-by :zig (:cases result)))))
       "## Machine-readable evidence and reproduction\n\n"
       (link "Classified case report" "build/jvm-audit-full/classification-20260925/report.edn")
       " retains each original form/output/error alongside its reviewed finding and "
       "independent recheck records. The source diagnostics were not overwritten.\n\n"
       "```clojure\n(require '[learn.jvm-audit-classification :as classification])\n"
       "(classification/write!)\n```\n\n"
       "This command rebuilds the ledger from saved reviewed shards and rejects missing, "
       "duplicate, out-of-range or incomplete classifications. It does not pretend to rerun "
       "native expressions. To rerun native probes use learn.jvm-audit-runner/run-parallel!.\n"))

(defn write! []
  (let [root "build/jvm-audit-full/classification-20260925"
        report (read-edn "build/jvm-audit-full/reviewed-20260925/report.edn")
        shards (mapv #(read-edn (io/file root (str % ".edn"))) ["start" "mid-a" "mid-b" "end"])
        result (combine report shards)
        expected-files (set (map :zig (:examples report)))
        reviewed-files (mapv :zig (:file-reviews result))]
    (when-not (and (= expected-files (set reviewed-files))
                   (= (count expected-files) (count reviewed-files)))
      (throw (ex-info "File review coverage mismatch" {:reviewed reviewed-files})))
    (let [by-id (into {} (map (juxt (juxt :zig :index) identity) (:cases result)))]
      (doseq [c (:cases result)
              :let [{:keys [category origin]} (:classification c)]
              :when (= :cascading-failure category)]
        (when-not (or (= :load origin)
                      (and (integer? origin)
                           (= :failed (:status (get by-id [(:zig c) origin])))))
          (throw (ex-info "Cascade lacks a recorded origin"
                          (select-keys c [:zig :index :classification]))))))
    (runner/write-report! (str (io/file root "report.edn")) result)
    (spit "JVM_AUDIT_CLASSIFIED_2026-09-25.md" (markdown result))
    {:files (:files result) :cases (count (:cases result))
     :failure-summary (:failure-summary result)
     :load-blocked-summary (:load-blocked-summary result)
     :report "JVM_AUDIT_CLASSIFIED_2026-09-25.md"}))
