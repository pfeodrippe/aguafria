(ns learn.jvm-audit-report
  "Readable coverage and failure evidence for a completed all-file audit."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def failure-statuses
  #{:failed :timeout :worker-exited :worker-error})

(defn- failure [c]
  (contains? failure-statuses (:status c)))

(defn- count-cases [pred example]
  (count (filter pred (:cases example))))

(defn- path-link [example]
  (str "[" (.getName (io/file (:source example))) "](<"
       (.getCanonicalPath (io/file "resources" (:source example))) ">)"))

(defn- file-id [example]
  (str/replace (:source example) #"[^a-zA-Z0-9_-]" "_"))

(defn- evidence-link [root example]
  (str "[cases](<" (or (:evidence-path example)
                           (.getCanonicalPath (io/file root "files" (file-id example) "result.edn"))) ">)"))

(defn- outcome [example]
  (let [load (get-in example [:load :status])
        expected (:upstream-expectation example)
        test-error? (str/includes?
                     (slurp (io/file "resources/upstream/doc/langref" (:zig example)))
                     "// test_error=")]
    (cond
      (= :inventory-error (:completion example)) "Inventory error"
      (= :target-specific load) "Target-specific; not executed"
      (= :load-failed load)
      (str "Load failed" (when (or test-error? (= :compile-failure expected))
                           " (upstream expects an error)"))
      (some failure (:cases example))
      (str "Observed failures" (when (or test-error? (= :runtime-failure expected))
                                  " (upstream expects an error)"))
      (some #(= :blocked-by-worker (:status %)) (:cases example)) "Worker interruption; incomplete"
      (some #(= :passed (:status %)) (:cases example)) "Executed checks passed; exclusions listed"
      :else "No executable checks completed")))

(defn markdown [report root]
  (let [examples (:examples report)
        cases (mapcat :cases examples)
        counts (frequencies (map :status cases))
        failures (filter failure cases)]
    (str "# Full Learn JVM audit — 2026-09-25\n\n"
         "## Scope\n\n"
         "All **" (count examples) " authored Learn example files** were submitted to "
         (:jobs report) " isolated JVM workers. Native calls execute in-process inside each worker; "
         "this does not replace the user-facing JVM API with a subprocess. Workers are reused "
         "for independent files, with separate native build caches, per-case checkpoints, "
         "timeouts and replacement after native panics.\n\n"
         "Elapsed wall time: " (format "%.1f" (/ (:elapsed-ms report) 1000.0)) " seconds. "
         "Cases recorded: **" (count cases) "**. Passing probes: **" (get counts :passed 0)
         "**. Failed/interrupted executed probes: **" (count failures) "**.\n\n"
         "**This is an audit, not an all-green claim.** A passing probe means evaluation and printing "
         "completed without throwing (including any authored assertions). It is not a semantic "
         "equivalence proof for arbitrary intermediate values. A passed function-Var inspection is not a "
         "passed invocation. Bodies needing arguments, branch/lexical context, container declaration "
         "context, foreign-target execution, and cases blocked by loading are explicit exclusions. "
         "No arguments were guessed. Whole contextual forms can pass while their detached descendants "
         "remain unexecuted. Workers retain compiler/native state across files, and repeated prefix "
         "evaluation also shares native state within a file: "
         "failures require triage before being called independent bugs. Earlier failed native "
         "adapters and missing build/link setup can also produce cascading failures.\n\n"
         "Expected upstream compile/runtime failures are annotated, **not automatically counted as "
         "verified expected failures**; matching the diagnostic is a separate check.\n\n"
         "## Case dispositions\n\n| Disposition | Count |\n|---|---:|\n"
         (apply str (for [[status n] (sort-by (comp str key) counts)]
                      (str "| `" (name status) "` | " n " |\n")))
         "\n## Every file\n\n"
         "Passed/failed counts refer to probes, not independent defects. Other cases are "
         "unexecuted or blocked; open the case evidence for the exact form, location, output, "
         "exception and disposition.\n\n"
         "| Source | Passed | Failed/interrupted | Other | Outcome | Evidence |\n"
         "|---|---:|---:|---:|---|---|\n"
         (apply str
                (for [e examples
                      :let [passed (count-cases #(= :passed (:status %)) e)
                            failed (count-cases failure e)]]
                  (str "| " (path-link e) " | " passed " | " failed " | "
                       (- (count (:cases e)) passed failed) " | " (outcome e) " | "
                       (evidence-link root e) " |\n")))
         "\n## First failure evidence per file\n\n"
         "Each raw case report retains **all** failures, not just the first shown here. "
         "These excerpts are observations; they are not all proven compiler defects.\n\n"
         (apply str
                (for [e examples
                      :let [c (first (filter failure (:cases e)))
                            load-error (when (= :load-failed (get-in e [:load :status])) (:load e))]
                      :when (or c load-error)]
                  (str "### " (.getName (io/file (:source e))) "\n\n"
                       (path-link e) " — " (outcome e) ". " (evidence-link root e) "\n\n"
                       (when c (str "```clojure\n" (pr-str (:form c)) "\n```\n\n"))
                       "```text\n"
                       (let [message (or (:cause c) (:message c)
                                         (:cause load-error) (:message load-error)
                                         (pr-str (:worker-event c)))]
                         (subs message 0 (min 1800 (count message))))
                       "\n```\n\n")))
         "## Reproduction\n\n"
         "From an owned Learn nREPL started with `clojure -M:dev:nrepl`:\n\n"
         "```clojure\n(require '[learn.jvm-audit-runner :as audit])\n"
         "(audit/run-parallel! {:jobs 4})\n```\n\n"
         "Raw aggregate: [report.edn](<" (.getCanonicalPath (io/file root "report.edn")) ">). "
         "Each worker log and interrupted-case checkpoint remains under the same run directory.\n")))

(defn write! [root destination]
  (let [report (edn/read-string (slurp (io/file root "report.edn")))]
    (spit destination (markdown report root))
    {:report destination :files (count (:examples report)) :summary (:summary report)}))
