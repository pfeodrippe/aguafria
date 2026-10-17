(ns learn.editor-check
  "Cold-bundle verification of the exact aguafria-eval-buffer workload.
  Uses the public aguafria.zig.check checker and its isolated JVM workers."
  (:require [aguafria.zig.check :as check]
            [aguafria.zig.repl :as repl]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [learn.editor-check-order :as order]
            [learn.editor-check-expectations :as expectations]
            [learn.editor-check-linkage :as linkage])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(defn evaluate!
  "Worker entry: evaluate each whole planned form once, stopping on the first
  exception, exactly as the editor does. No native-context replacements, calls
  to main, assertion instrumentation, or extra native scope are introduced.
  Source loading is performed by check-worker before this entry is invoked."
  [namespace path checkpoint]
  (let [source (slurp path)
        rows (:rows (repl/plan source (str namespace)))
        completed (atom [])
        current (atom nil)
        save! (fn [status]
                (io/make-parents checkpoint)
                (spit checkpoint
                      (pr-str {:namespace namespace :file path :status status
                               :planned rows :completed @completed :current-row @current
                               :remaining (vec (drop (count @completed) rows))})))]
    (try
      (binding [*ns* (the-ns namespace) *file* path]
        (doseq [row rows]
          (reset! current row)
          (save! :evaluating)
          (if (= :skipped (:status row))
            (swap! completed conj row)
            (let [source (:expression row)
                  expression (if-let [type (:type-context row)]
                               (str "(aguafria.keyword/as " source " " type ")") source)
                  wrapped (str "^{:aguafria.explain/source " (pr-str source) "} "
                               "(aguafria.zig/explain! " expression ")")
                  reader (doto (LineNumberingPushbackReader. (StringReader. wrapped))
                           (.setLineNumber (:line row)))
                  result (eval (read reader))]
              ;; CIDER also prints every result, potentially realizing a native
              ;; value. Keep that work inside the checker's strict audit.
              (swap! completed conj
                     (assoc row :status :evaluated :result (pr-str result)
                            :returned-native-error? (boolean (value/zig-error? result))))))
          (save! :evaluating)))
      (let [evaluated (count (filter #(= :evaluated (:status %)) @completed))
            skipped (- (count @completed) evaluated)
            returned-errors (filterv :returned-native-error? @completed)
            status (cond (zero? evaluated) :not-exercised
                         (pos? skipped) :partial
                         :else :passed)]
        (save! status)
        ;; Error-set constants are legitimate values, not failed evaluations.
        ;; Match CIDER: only a thrown failure stops the form sequence.
        {:namespace namespace :editor-status status :evaluated evaluated :skipped skipped
         :returned-error-values (count returned-errors)})
      (catch Throwable e
        (save! :failed)
        (throw e)))))

(defn prepare!
  "Prepare the selected HTML batch (or cumulative prefix) through the public
  precompile API, without evaluating bodies. Use -Daguafria.cache-dir for a new
  isolated cache. A producer success alone is not batch acceptance."
  [{:keys [report-file parallelism linkage-contexts] :or {parallelism 16} :as options}]
  (when-not report-file
    (throw (ex-info "Supply :report-file" {})))
  (let [inventory (order/inventory options)
        receipt (str report-file ".html-order.edn")]
    (when (or (.exists (io/file report-file)) (.exists (io/file receipt)))
      (throw (ex-info "Batch preparation receipt already exists" {:report-file report-file})))
    (io/make-parents receipt)
    (spit receipt (pr-str inventory))
    (let [linked (linkage/prepare! inventory (str report-file ".linkage.edn"))
          _ ((requiring-resolve 'aguafria.zig/configure!) (:configuration linked))
          contexts (vec (distinct (concat linkage-contexts (:linkage-contexts linked))))
          report ((requiring-resolve 'aguafria.zig.precompile/precompile!)
                  {:analyze (mapv :namespace (:selected inventory))
                   :source-files (into {} (map (juxt :namespace :file)) (:selected inventory))
                   :parallelism parallelism :report-file report-file
                   :linkage-contexts contexts})]
      (prn {:report-file report-file :selected (count (:selected inventory))
            :analysis-statuses (frequencies (map #(or (:status %) :analyzed) (:analysis report)))})
      (select-keys report [:cache-dir :namespace-admission :coverage]))))

(defn check!
  "Run the existing checker against every Learn source and an already-built
  cache. Example, from examples/learn:
    clojure -X:editor-check :prepared-cache-dir \"/absolute/cache\"
      :report-dir \"/new/report/directory\" :parallelism 4
  An optional :precompile-report is recorded as producer provenance, not used
  to exclude failed or rejected namespaces. With :html-file, select named examples
  in document order using :batch N (50 by default), or :through-batch N to rerun
  all batches through N. Displayed named snippets are included, not discarded."
  [{:keys [source-dir report-dir prepared-cache-dir precompile-report parallelism]
    :or {source-dir "resources/learn/example" parallelism 4}
    :as options}]
  (when-not (and report-dir prepared-cache-dir)
    (throw (ex-info "Supply :report-dir and :prepared-cache-dir" {})))
  (let [inventory (order/inventory (assoc options :source-dir source-dir))]
    ;; The public checker owns creation of report-dir. Preserve the frozen
    ;; inventory beside it so it survives an interrupted run as well.
    (let [receipt (str report-dir ".html-order.edn")]
      (when (or (.exists (io/file report-dir)) (.exists (io/file receipt)))
        (throw (ex-info "Editor checker run directory or order receipt already exists"
                        {:report-dir report-dir :receipt receipt})))
      (io/make-parents receipt)
      (spit receipt (pr-str inventory)))
    (let [linkage-file (when precompile-report (str precompile-report ".linkage.edn"))
          linked (when (and linkage-file (.isFile (io/file linkage-file)))
                   (linkage/read! linkage-file))
          scenarios
          (mapv (fn [{:keys [namespace file]}]
                  {:id namespace :call 'learn.editor-check/evaluate!
                   :load-files [file]
                   :args [namespace file (str (io/file report-dir "editor" (str namespace ".edn")))]
                   :expect (expectations/expectation namespace)}) (:selected inventory))
          report (check/check! (merge (select-keys options [:heap-mib :timeout-seconds])
                                      {:report-dir report-dir :prepared-cache-dir prepared-cache-dir
                                       :strict-load? true :compilation-modes [:prepared]
                                       :compiler-configuration (or (:configuration linked) {})
                                       :parallelism parallelism :scenarios scenarios}))]
      (spit (io/file report-dir "html-order.edn") (pr-str inventory))
      (spit (io/file report-dir "learn-inventory.edn")
            (pr-str {:namespaces (mapv :id scenarios) :count (count scenarios)
                     :precompile-report precompile-report :linkage-receipt linkage-file
                     :source-dir source-dir
                     :order (:order inventory) :batch (:batch inventory)
                     :through-batch (:through-batch inventory) :batch-size (:batch-size inventory)}))
      (println (pr-str {:directory (:directory report) :namespaces (count scenarios)
                        :statuses (frequencies (map :status (:results report)))
                        :accepted? (:accepted? report)}))
      (select-keys report [:directory :accepted?]))))
