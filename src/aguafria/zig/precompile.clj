(ns aguafria.zig.precompile
  "Explicit, compile-only preparation of persistent native artifacts."
  (:require [aguafria.zig.bundle :as bundle]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io])
  (:import [java.util.concurrent Callable Executors]))

(defn- validate-options! [options]
  (when-not (and (map? options)
                 (every? #{:namespaces :calls :coercions :analyze :source-dirs :parallelism :report-file :bundle? :ignore} (keys options))
                 (or (nil? (:ignore options)) (sequential? (:ignore options)) (set? (:ignore options)))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:ignore options))
                 (or (not (contains? options :bundle?)) (boolean? (:bundle? options)))
                 (every? sequential? (vals (select-keys options [:namespaces :calls :coercions :analyze :source-dirs])))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:namespaces options))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:analyze options))
                 (every? string? (:source-dirs options))
                 (or (nil? (:parallelism options))
                     (and (integer? (:parallelism options)) (<= 1 (:parallelism options) 16)))
                 (or (nil? (:report-file options)) (string? (:report-file options)))
                 (every? #(and (map? %)
                               (= #{:function :args} (set (keys %)))
                               (qualified-symbol? (:function %))
                               (vector? (:args %))) (:calls options))
                 (or (seq (:namespaces options)) (seq (:calls options))
                     (seq (:coercions options)) (seq (:analyze options))
                     (seq (:source-dirs options))))
    (throw (ex-info "Expected :namespaces [namespace ...] and/or :calls [{:function namespace/function :args [type ...]}]"
                    {:options options}))))

(defn- source-namespaces [directories]
  (->> directories
       (mapcat (fn [directory]
                 (let [root (io/file directory)]
                   (when-not (.isDirectory root)
                     (throw (ex-info "Analysis source directory does not exist" {:directory directory})))
                   (for [file (sort-by str (file-seq root))
                         :when (and (.isFile file) (.endsWith (.getName file) ".clj"))]
                     (with-open [reader (java.io.PushbackReader. (io/reader file))]
                       (let [form (binding [*read-eval* false] (read reader))]
                         (when-not (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                           (throw (ex-info "Expected an ns form at start of source file" {:file (str file)})))
                         (second form)))))))
       distinct
       vec))

(defn- analyze-namespaces! [namespaces parallelism report-file]
  ;; Load sequentially: Clojure namespace initialization is not a parallel
  ;; compilation workload. Analysis/handler compilation use bounded workers.
  (let [loads (mapv (fn [namespace]
                      (try
                        (binding [runtime/*source-only-registration?* true]
                          (require namespace))
                        {:namespace namespace}
                        (catch Exception error
                          (assoc (discovery/error-report error)
                                 :namespace namespace :status :load-failed))))
                    namespaces)
        prepare (requiring-resolve 'aguafria.zig.discovery/prepare!)]
    (with-open [executor (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))]
      (let [jobs (mapv (fn [{:keys [namespace status] :as loaded}]
                         (.submit executor
                                  ^Callable
                                  (bound-fn []
                                    (let [report (if status loaded
                                                     (try (prepare namespace)
                                                          (catch Exception error
                                                            (assoc (discovery/error-report error)
                                                                   :namespace namespace :status :analysis-failed))))
                                          checkpoint (io/file (str report-file ".d")
                                                              (str namespace ".edn"))]
                                      (io/make-parents checkpoint)
                                      (spit checkpoint (pr-str report))
                                      report)))) loads)]
        (mapv #(.get ^java.util.concurrent.Future %) jobs)))))

(defn coverage
  "Summarize the emitted-operation inventory without treating observation,
  partial preparation, or skipped work as successful preparation. This is not
  a claim to have evaluated every source form or instantiated every generic."
  [analysis]
  (let [functions (mapcat :functions analysis)
        operations (mapcat :operations analysis)
        non-call? #(contains? #{:type-declaration :compiler-directive} (:reason %))
        deferred? #(and (= :observed (:status %))
                        (seq (:handlers %))
                        (every? (fn [handler] (= :deferred (:status handler))) (:handlers %)))
        runtime-operations (remove #(or (non-call? %) (deferred? %)) operations)
        handlers (mapcat :handlers operations)
        prepared? #(and (= :observed (:status %))
                        (seq (:handlers %))
                        (every? (fn [handler] (= :prepared (:status handler))) (:handlers %)))]
    {:scope :emitted-operations
     :namespaces {:attempted (count analysis)
                  :statuses (frequencies (map #(or (:status %) :analyzed) analysis))
                  :baseline-failures (count (filter #(and (:baseline %)
                                                          (not (zero? (get-in % [:baseline :exit])))) analysis))}
     :operations {:total (count operations)
                  :statuses (frequencies (map :status operations))
                  :fully-prepared (count (filter prepared? operations))
                  :not-fully-prepared (count (remove prepared? operations))}
     :non-call-operations (frequencies (map :reason (filter non-call? operations)))
     ;; These calls construct JVM syntax values; their enclosing native calls
     ;; are separate operations and still have to be prepared successfully.
     :deferred-calls {:total (count (filter deferred? operations))
                      :reason :result-context-required}
     :runtime-candidates {:total (count runtime-operations)
                          :fully-prepared (count (filter prepared? runtime-operations))
                          :not-fully-prepared (count (remove prepared? runtime-operations))}
     :incomplete-operation-groups
     (frequencies
      (map (fn [operation]
             (if (= :observed (:status operation))
               (let [reasons (->> (:handlers operation)
                                  (remove #(= :prepared (:status %)))
                                  (map #(or (:reason %) (:status %)))
                                  distinct sort vec)]
                 (if (seq reasons) reasons [:no-handlers]))
               [(or (:reason operation) (:status operation))]))
           (remove prepared? operations)))
     :handler-records (frequencies (map :status handlers))
     :declared-functions {:total (count functions)
                          :statuses (frequencies (map :status functions))
                          :skip-reasons (frequencies (keep :reason functions))}
     :gaps (frequencies
            (concat (keep #(when-not (= :observed (:status %))
                             (or (:reason %) (:status %))) operations)
                    (keep #(when-not (= :prepared (:status %))
                             (or (:reason %) (:status %))) handlers)))}))

(defn precompile!
  "Compile persistent JVM artifacts explicitly, never as part of :prepare.

  :namespaces compiles concrete native functions without calling their bodies.
  Generic functions, comptime results, externs, tests and process entry hosts
  are reported as skipped, not executed to discover their signatures.

  :calls supplies exact native argument types for builtin/operator/imported or
  generic call adapters. Use {:comptime value} for source-level comptime inputs.
  No runtime call is made. Unsupported adapter paths fail explicitly.

  :coercions lists native schemas for value constructors, including the storage
  adapters that keep numeric results addressable on the JVM.

  :analyze lists namespaces whose emitted operations are inspected by Zig and
  used to prepare supported handlers automatically. It also prepares their
  concrete top-level callables and reports each unsupported or failed function
  separately, including functions not called by another example form.
  :source-dirs discovers all
  .clj namespaces in directories already on the classpath. Per-operation gaps
  and compiler errors remain in :analysis. :parallelism defaults to 2; analysis
  runs on bounded virtual-thread workers. Reports persist under :report-file
  (default .aguafria/precompile/report.edn).

  :ignore is a collection of namespace symbols excluded from explicit function,
  call and source-directory analysis requests, before loading them. The report's
  :ignored entries are not counted as prepared. This does not suppress imports
  transitively required by another selected namespace or change normal execution.

  Compatible generated JVM handlers, including cached ones, are compiled into
  one immutable shared-cache library. Incompatible configurations fail instead
  of silently splitting the bundle. :bundle? false keeps standalone artifacts.
  The bundle is linked only after semantic validation; native code is never
  loaded during preparation. Unsupported linker configurations stay standalone
  and are listed in :bundles :standalone.

  Namespace loading still runs ordinary Clojure top-level code and macros; Zig
  performs its normal comptime analysis. No function/test/comment body is run.
  The normal cache invalidation rules apply; handles and state are not saved."
  [{:keys [namespaces calls coercions analyze source-dirs parallelism report-file bundle? ignore]
    :or {parallelism 2 report-file ".aguafria/precompile/report.edn" bundle? true}
    :as options}]
  (validate-options! options)
  (binding [runtime/*compile-only?* true
            bundle/*preparing* (when bundle? (atom {}))]
    (let [started (System/nanoTime)
          excluded (set ignore)
          analysis-namespaces (vec (distinct (concat analyze (source-namespaces source-dirs))))
          call-namespace (comp symbol namespace :function)
          selected (distinct (concat namespaces analysis-namespaces (map call-namespace calls)))
          ignored (mapv (fn [namespace]
                          {:namespace namespace :status :ignored :reason :explicit-ignore})
                        (filter excluded selected))
          namespaces (remove excluded namespaces)
          calls (remove #(excluded (call-namespace %)) calls)
          analysis-namespaces (filterv #(not (excluded %)) analysis-namespaces)]
      (binding [runtime/*source-only-registration?* true]
        (doseq [namespace (distinct (concat namespaces
                                            (map (comp symbol namespace :function) calls)))]
          (require namespace)))
      (let [report {:functions (into [] (mapcat runtime/precompile-functions!) (distinct namespaces))
                    :calls (mapv (requiring-resolve 'aguafria.zig.jvm/precompile-call!) calls)
                    :coercions (mapv (requiring-resolve 'aguafria.zig.jvm/precompile-coercion!) coercions)
                    :analysis (analyze-namespaces! analysis-namespaces parallelism report-file)
                    :ignored ignored
                    :cache-dir (:cache-dir (runtime/configuration))
                    :duration-ms (/ (- (System/nanoTime) started) 1e6)}
            report (assoc report :coverage (assoc-in (coverage (:analysis report))
                                                     [:namespaces :ignored] (count ignored))
                          :bundles (when bundle?
                                     (runtime/finish-precompile-bundles! bundle/*preparing*))
                          :duration-ms (/ (- (System/nanoTime) started) 1e6))]
        (io/make-parents report-file)
        (spit report-file (pr-str report))
        report))))
