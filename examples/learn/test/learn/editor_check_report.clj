(ns learn.editor-check-report
  "Summarize existing checker receipts; never evaluates lessons or compiles code."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [learn.editor-check-failures :as failures]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(defn- read-edn [file]
  (when (.isFile (io/file file))
    (with-open [r (java.io.PushbackReader. (io/reader file))] (edn/read r))))

(defn- short-text [x limit]
  (let [s (str/replace (str x) #"\s+" " ")]
    (if (> (count s) limit) (str (subs s 0 limit) "…") s)))

(defn- expected [upstream]
  (let [s (if (and upstream (.isFile (io/file upstream))) (slurp upstream) "")]
    (cond
      (re-find #"(?m)^// (?:exe|test)=build_fail" s) :compile-failure
      ;; test_error describes runner stderr, including allocator leak reports;
      ;; it is not evidence that compilation itself should fail.
      (re-find #"(?m)^// test_error=" s) :test-failure
      (re-find #"(?m)^// (?:exe=fail|test_safety=)" s) :runtime-failure
      (re-find #"(?m)^// target=" s) :target-specific
      (re-find #"(?m)^// syntax\s*$" s) :syntax-only
      :else :success-or-context)))

(defn- artifact-kind [attempts]
  (let [command (str/join " " (:command (first attempts)))
        module (second (re-find #"/(aguafria_jvm_[^/]+)/" command))]
    (cond (= "test-obj" (second (:command (first attempts)))) "native-test-definition"
          (= "test" (second (:command (first attempts)))) "compile-time-query"
          module (str/replace module #"-[0-9a-f]{16,}$" "")
          (str/includes? command "/learn_example_") "namespace-image"
          :else "other-native-artifact")))

(defn- diagnostic-lines [text]
  (vec (distinct (map second (re-seq #"(?m)^.*?error: (.+)$" (or text ""))))))

(defn- producer-diagnostics [analysis rejection]
  ;; Do not mistake the discovery probe's successful @compileLog protocol for
  ;; an application failure. Baseline failures and admission errors are direct
  ;; rejection evidence; planning messages are retained separately from probes.
  (vec (distinct
        (concat
         (when (and (number? (get-in analysis [:baseline :exit]))
                    (not (zero? (get-in analysis [:baseline :exit]))))
           (diagnostic-lines (get-in analysis [:baseline :diagnostics])))
         (mapcat #(diagnostic-lines (:err %)) (vals (:artifact-errors rejection)))
         (for [m (tree-seq coll? seq analysis) :when (map? m)
               k [:error :message :cause]
               :let [s (get m k)] :when (and (string? s) (not (str/blank? s)))]
           (short-text s 500))))))

(defn- meaningful-children [n]
  (when (and n (node/inner? n))
    (remove node/printable-only? (node/children n))))

(defn- token [n]
  (when (and n (= :token (node/tag n))) (node/sexpr n)))

(defn- test-snapshot [path attempts]
  (let [command (:command (first attempts))
        test-filter (second (drop-while #(not= "--test-filter" %) command))]
    (when test-filter
      (try
        (let [forms (meaningful-children (parser/parse-string-all (slurp path)))
              namespace-form (node/sexpr (first forms))
              aliases (into #{"aguafria.zig"}
                            (keep (fn [v]
                                    (when (and (vector? v) (= 'aguafria.zig (first v)))
                                      (some-> (second (drop-while #(not= :as %) v)) str))))
                            (tree-seq coll? seq namespace-form))
              declarations (vec (keep (fn [form]
                                        (when (= :list (node/tag form))
                                          (let [[h n] (meaningful-children form)
                                                head (token h)]
                                            (when (and (symbol? head) (aliases (namespace head))
                                                       (#{"defn" "defn-" "defconst" "defvar" "defstruct" "defenum" "defunion"
                                                          "defimport" "defraw" "deffield" "defcomptime" "defextern"
                                                          "defexternvar" "deftest"} (name head)))
                                              {:kind (name head) :name (str (token n))
                                               :line (:row (meta form))})))) forms))
              index (first (keep-indexed (fn [i d]
                                          (when (and (= "deftest" (:kind d))
                                                     (str/ends-with? test-filter (str ".test." (:name d)))) i))
                                        declarations))]
          (if (some? index)
            {:filter test-filter :namespace (second namespace-form) :source (str path)
             :declaration (nth declarations index)
             :following-declarations (- (count declarations) index 1)
             :kind (if (= index (dec (count declarations))) :complete :prefix)}
            {:filter test-filter :kind :unknown}))
        (catch Exception e {:filter test-filter :kind :unknown :reason (ex-message e)})))))

(defn- requested-test-snapshot [path attempts failure]
  (let [primary (test-snapshot path attempts)]
    (if (= :unknown (:kind primary))
      (or (some (fn [source]
                  (when (and (string? source) (str/ends-with? source ".clj"))
                    (let [resource (if (.isFile (io/file source)) source (io/resource source))
                          snapshot (when resource (test-snapshot resource attempts))]
                      (when (#{:complete :prefix} (:kind snapshot))
                        (assoc snapshot :transitive? true)))))
                (keep #(get-in % [:data :clojure.error/source]) (:via failure)))
          primary)
      primary)))

(defn data
  [{:keys [check-dir precompile-report resources-dir]
    :or {resources-dir "resources"}}]
  (let [final (read-edn (io/file check-dir "report.edn"))
        configuration (read-edn (io/file check-dir "configuration.edn"))
        html-order (read-edn (io/file check-dir "html-order.edn"))
        positions (into {} (map (juxt :namespace :position)) (:selected html-order))
        producer (read-edn precompile-report)
        results (or (:results final)
                    (mapv read-edn (sort-by str (filter #(re-matches #"prepared-\d+\.edn" (.getName %))
                                                        (.listFiles (io/file check-dir))))))
        overrides (read-edn (io/file resources-dir "learn/overrides.edn"))
        by-source (into {} (keep (fn [[zig v]] (when (:source v) [(:source v) zig]))) overrides)
        analyses (into {} (map (juxt :namespace identity) (:analysis producer)))
        rejected-analyses (into {} (map (juxt :namespace identity) (get-in producer [:rejected-preparation :analysis])))
        rejections (into {} (map (juxt :namespace identity) (get-in producer [:namespace-admission :rejected-namespaces])))
        scenarios (into {} (map (juxt :id identity) (:scenarios configuration)))
        expected-bundles (set (keep :id (get-in producer [:bundles :packs])))
        rows (mapv
              (fn [r]
                (let [n (:scenario r)
                      scenario (get scenarios n)
                      path (first (:load-files scenario))
                      source (some-> path (str/split #"/resources/") last)
                      zig (get by-source source)
                      upstream (when zig (io/file resources-dir "upstream/doc/langref" zig))
                      editor (read-edn (last (:args scenario)))
                      attempts (:final-compiler-attempts r)
                      events (:final-all-native-events r)
                      failure (:failure r)
                      analysis (or (get rejected-analyses n) (get analyses n))
                      rejection (get rejections n)
                      message (str (or (:cause failure) (some-> failure :via last :message)
                                       (:reason r) ""))
                      stage (or (:failure-stage r) (:stage (first attempts))
                                (when editor :evaluate) :load)
                      row {:namespace n :html-position (get positions n) :check-status (:status r) :accepted? (:accepted? r)
                           :coverage (if (= :expected-exception (:status r)) :expected-negative (:status editor))
                           :editor-status (:status editor) :stage stage :reason (:reason r)
                           :editor-observations (mapv #(select-keys % [:expression :status :result])
                                                      (:completed editor))
                           :upstream (str upstream) :upstream-expectation (expected upstream)
                           :message message :failure-types (mapv :type (:via failure))
                           :producer-status (or (:status (get analyses n)) :analyzed)
                           :producer-diagnostics (producer-diagnostics analysis rejection)
                           :producer-rejection-reason (:reason rejection)
                           :producer-baseline-exit (get-in analysis [:baseline :exit])
                           :test-snapshot (requested-test-snapshot path attempts failure)
                           :compiler-attempts (count attempts) :artifact-kind (artifact-kind attempts)
                           :compiler-stages (frequencies (map :stage attempts))
                           :disk-fallbacks (count (filter #(= :disk-cache-hit (:event %)) events))
                           :event-counts (frequencies (map :event events))
                           :bundle-ids (set (keep :bundle-id events))
                           :evaluated (count (filter #(= :evaluated (:status %)) (:completed editor)))
                           :skipped (count (filter #(= :skipped (:status %)) (:completed editor)))
                           :remaining (count (:remaining editor))
                           :line (get-in editor [:current-row :line])
                           :form (get-in editor [:current-row :expression])
                           :worker (:worker r)}]
                  (if (:accepted? r)
                    row
                    (let [group (failures/classify row)]
                      (assoc row :repair-group group :category (first (failures/description group)))))))
              results)
        observed (reduce set/union #{} (map :bundle-ids rows))]
    {:complete? (boolean final) :accepted? (:accepted? final)
     :check-dir check-dir :precompile-report precompile-report
     :configuration (select-keys configuration [:parallelism :heap-mib :timeout-seconds
                                                :strict-load? :prepared-cache-dir :compilation-modes])
     :selected (count (:scenarios configuration)) :checked (count rows)
     :statuses (frequencies (map :check-status rows))
     :coverage (frequencies (map :coverage rows))
     :evaluated (reduce + (map :evaluated rows)) :skipped (reduce + (map :skipped rows))
     :remaining (reduce + (map :remaining rows))
     :compiler-attempts (reduce + (map :compiler-attempts rows))
     :compiler-stages (merge-with + (apply merge-with + {} (map :compiler-stages rows)))
     :disk-fallbacks (reduce + (map :disk-fallbacks rows))
     :expected-bundles expected-bundles :observed-bundles observed
     :unexpected-bundles (set/difference observed expected-bundles)
     :producer (select-keys producer [:duration-ms :parallelism :coverage :compiler-work])
     :producer-analysis-statuses (frequencies (map #(or (:status %) :analyzed) (:analysis producer)))
     :producer-analysis-count (count (:analysis producer))
     :groups (frequencies (keep :category rows))
     :rows (sort-by (juxt #(or (:html-position %) Long/MAX_VALUE) (comp str :namespace)) rows)}))

(defn inspect! [options]
  (let [d (data options)]
    (spit (io/file (:check-dir options) "summary.edn") (pr-str d))
    (prn (dissoc d :rows :producer))
    (prn :producer (:producer d))
    nil))

(defn- failure-entry [r]
  (str "- **" (when (:html-position r) (str "#" (:html-position r) " "))
       "`" (:namespace r) "`**\n"
       "  - Consumer: " (name (:stage r)) (when (:line r) (str ", line " (:line r)))
       "; " (short-text (:message r) 300) ".\n"
       "  - Producer: " (name (:producer-status r))
       (when (:producer-rejection-reason r) (str " / " (name (:producer-rejection-reason r))))
       "; upstream intent: " (name (:upstream-expectation r)) ".\n"
       (apply str (for [diagnostic (take 4 (:producer-diagnostics r))]
                    (str "  - Producer diagnostic: " (short-text diagnostic 400) "\n")))
       (when-let [snapshot (:test-snapshot r)]
         (str "  - Requested test: `" (:filter snapshot) "`; source snapshot: " (name (:kind snapshot))
              (when (:transitive? snapshot)
                (str "; transitive owner `" (:namespace snapshot) "`"))
              (when (some? (:following-declarations snapshot))
                (str "; " (:following-declarations snapshot) " later native declarations")) ".\n"))
       (when (:form r) (str "  - Form: `" (short-text (:form r) 240) "`\n"))
       "  - Evidence: `" (.getName (io/file (get-in r [:worker :directory])))
       "/result.edn`; producer analysis/admission records selected by `" (:namespace r) "`.\n"))

(defn report!
  [{:keys [output-file] :as options}]
  (let [d (data options)
        _ (when-not (:complete? d) (throw (ex-info "The checker has not finished" (dissoc d :rows :producer))))
        groups (sort-by (fn [[k rs]] [(- (count rs)) (str k)])
                        (group-by :repair-group (filter :repair-group (:rows d))))
        passed (filter :accepted? (:rows d))
        text
        (str "# Learn cold precompile and exact editor-form check\n\n"
             "## Result\n\n" (if (:accepted? d) "**CHECKER ACCEPTED — inspect coverage below.** " "**NOT GREEN.** ") (:checked d) "/" (:selected d) " namespaces checked through `aguafria.zig.check/check!`: "
             (pr-str (:statuses d)) ".\n\n"
             "- Whole forms evaluated: " (:evaluated d) "; planner-skipped forms: " (:skipped d)
             "; remaining planned forms after a stopped evaluation: " (:remaining d) ".\n"
             "- Coverage statuses: `" (pr-str (:coverage d)) "`. A load failure has no editor checkpoint; its body forms are not counted as exercised.\n"
             "- Blocked compiler attempts: **" (:compiler-attempts d) "**, by stage `" (pr-str (:compiler-stages d)) "`. These are failed preparation guarantees, not successful warmups.\n"
             "- Non-bundle disk-cache fallback events: **" (:disk-fallbacks d) "**.\n"
             "- Producer bundle IDs: `" (pr-str (:expected-bundles d)) "`. Observed: `" (pr-str (:observed-bundles d)) "`. Unexpected IDs: `" (pr-str (:unexpected-bundles d)) "`.\n\n"
             "## Method and limits\n\n"
             "Producer parallelism: " (get-in d [:producer :parallelism]) ". A successful producer exit means preparation finished, not that every namespace or operation was admitted. Cache coldness must be established from separate deletion/producer receipts, not inferred from this report. Producer analysis statuses: `"
             (pr-str (:producer-analysis-statuses d)) "` across " (:producer-analysis-count d) " namespaces.\n\n"
             "Recorded checker configuration: `" (pr-str (:configuration d)) "`. Reusing a provided cache does not run another producer. In strict prepared mode the compiler policy rejects commands before invoking the compiler; metadata/version queries are separate. Bundle/disk events are intercepted at `explain/event!`, including events hidden by the editor's nested `explain!` reporter.\n\n"
             "`learn.editor-check/evaluate!` uses the real `repl/plan`, initializer type-context cast, exact source text and `aguafria.zig/explain!` wrapper. It evaluates each whole form once, prints its result and stops on the first exception. Planner skips remain skips. It does not call main, replace test bodies, instrument assertions or introduce native scopes. Returned error-set constants remain legitimate values, as in CIDER; thrown errors stop evaluation. Only explicitly reviewed exception contracts can accept that stop as an expected negative, still subject to the strict compiler/fallback audit.\n\n"
             "Upstream expectation labels below come only from the original Zig fixture directives. They are **not** proof that this run produced the intended diagnostic. All namespaces were attempted without an exclusion list. Missing preparation and source/context failures can mask later failures. No complete historical compiler baseline was run, so not every failure is claimed to be newly introduced.\n\n"
             "Evidence: `" (:check-dir d) "/report.edn`, per-worker receipts/journals/stdout/stderr, `editor/*.edn` checkpoints, and producer `" (:precompile-report d) "`. `summary.edn` retains compact per-namespace details.\n\n"
             "## Failure groups by repair, not by stopping stage\n\n"
             "The consumer's blocked command does not explain why preparation was absent. These groups also use preserved producer rejections and the requested test's source position. Rejected namespaces are separated from admitted test-object misses. A producer diagnostic may expose an earlier blocker; it does not prove the consumer reached that diagnostic. Remaining unknown recipe failures stay individually unresolved. Test snapshot grouping distinguishes a complete definition from a prefix with later declarations; it does not claim to prove the exact cache-key defect.\n\n"
             (apply str
                    (for [[group rs] groups
                          :let [[title fix acceptance] (failures/description group)]]
                      (str "### " (short-text title 200) " — " (count rs) (if (= 1 (count rs)) " namespace\n\n" " namespaces\n\n")
                           "**Fix or next investigation:** " fix "\n\n"
                           "**Acceptance check:** " acceptance "\n\n"
                           (apply str (map failure-entry rs)) "\n")))
             "## Checker-passing namespaces and actual coverage\n\n"
             "Acceptance requires no forbidden compiler attempt or disk fallback. Positive forms completed; `expected-negative` means the reviewed exception type, diagnostic, data and stage matched, not that the body completed successfully. `partial` and `not-exercised` are not full-body coverage.\n\n"
             (apply str (for [r passed]
                          (str "- " (when (:html-position r) (str "#" (:html-position r) " "))
                               "`" (:namespace r) "`: " (name (:coverage r)) "; " (:evaluated r)
                               " evaluated, " (:skipped r) " skipped; "
                               (get-in r [:event-counts :bundle-cache-hit] 0) " bundle hits.\n"
                               (when (= :expected-negative (:coverage r))
                                 (str "  - Matched at " (name (:stage r)) ": "
                                      (short-text (:message r) 240) ".\n")))))
             "\n## Fix order and acceptance gate\n\n"
             "1. Separate intentional negative/target-specific fixtures from positive editor regressions using producer diagnostics; do not delete them from the inventory.\n"
             "2. Repair positive namespace-load preparation gaps first, since they block every body in that namespace.\n"
             "3. Repair missing adapter families and bundle indexing, then ordinary source/context/value failures using the recorded first failing forms.\n"
             "4. Add each repaired form to cold prepared regression coverage. Repeat the fresh isolated cache → precompile → public checker workflow until positive namespaces have no compilation attempts or disk fallbacks.\n"
             "5. Validate intentional negative fixtures against their expected diagnostics separately. Report planner skips and environment-specific workloads explicitly.\n")]
    (spit (io/file (:check-dir options) "summary.edn") (pr-str d))
    (io/make-parents output-file)
    (spit output-file text)
    (prn {:report output-file :statuses (:statuses d) :groups (:groups d)})
    nil))
