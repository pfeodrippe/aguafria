(ns aguafria.zig.precompile
  "Explicit, compile-only preparation of persistent native artifacts."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.compiler-work :as compiler-work]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io])
  (:import [java.util.concurrent Callable Executors Semaphore]))

(defn- build-submitter [executor parallelism]
  (let [permits (Semaphore. (* 2 parallelism))]
    (fn [build]
      (.acquire permits)
      (try
        (let [configuration (runtime/configuration)]
          (.submit ^java.util.concurrent.ExecutorService executor
                   ^Callable
                   (bound-fn []
                     (try
                       (binding [runtime/*precompile-build-submitter* nil]
                         (runtime/call-with-precompile-configuration configuration build))
                       (finally (.release permits))))))
        (catch Throwable error
          (.release permits)
          (throw error))))))

(defn- resolve-namespace-image! [image]
  (if-let [job (:namespace-image-job image)]
    (merge (dissoc image :namespace-image-job)
           (try
             (.get ^java.util.concurrent.Future job)
             (catch java.util.concurrent.ExecutionException error
               (let [cause (ex-cause error)]
                 (when-not (instance? Exception cause) (throw cause))
                 (assoc (discovery/error-report cause)
                        :namespace (:namespace image) :status :failed)))))
    image))

(defn- resolve-precompile-images! [images]
  (doseq [[module image] (sort-by key @images)]
    (swap! images assoc module (resolve-namespace-image! image))))

(defn- validate-options! [options]
  (when-not (and (map? options)
                 (every? #{:namespaces :calls :coercions :analyze :source-dirs :parallelism :report-file :ignore} (keys options))
                 (or (nil? (:ignore options)) (sequential? (:ignore options)) (set? (:ignore options)))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:ignore options))
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

(defn- load-namespace! [namespace images]
  (let [before (set (runtime/registered-modules))]
    (binding [runtime/*source-only-registration?* true]
      (require namespace))
    (loop [pending (sort (set (conj (vec (remove before (runtime/registered-modules)))
                                    (str namespace))))]
      (when-let [module (first pending)]
        (if (contains? @images module)
          (recur (next pending))
          (let [configuration (or (get-in @runtime/*prepared-namespace-images*
                                          [module :configuration])
                                  (runtime/configuration))
                image (try
                        (runtime/call-with-precompile-configuration
                         configuration #(binding [bundle/*preparation-owner* (symbol module)]
                                          (runtime/precompile-namespace-load! module)))
                        (catch Exception error
                          (assoc (discovery/error-report error)
                                 :namespace (symbol module) :status :failed)))]
            (swap! images assoc module image)
            ;; The compilation snapshot includes dependencies that were already
            ;; loaded before this run, as well as newly required namespaces.
            (recur (concat (next pending) (:dependencies image)))))))
    (or (get-in @runtime/*prepared-namespace-images*
                [(str namespace) :configuration])
        (runtime/configuration))))

(defn- analyze-namespaces! [namespaces parallelism report-file images]
  ;; Load sequentially: Clojure namespace initialization is not a parallel
  ;; compilation workload. Analysis/handler compilation use bounded workers.
  (let [loads (mapv (fn [namespace]
                      (try
                        (let [configuration (load-namespace! namespace images)]
                          (cond-> {:namespace namespace :configuration configuration}
                            (= :no-native-declarations
                               (get-in @images [(str namespace) :reason]))
                            (assoc :status :skipped :reason :no-native-declarations)))
                        (catch Exception error
                          (assoc (discovery/error-report error)
                                 :namespace namespace :status :load-failed))))
                    namespaces)
        _ (resolve-precompile-images! images)
        prepare (fn [namespace]
                  (let [analysis (discovery/analyze! namespace)
                        exit (get-in analysis [:baseline :exit])]
                    (when-not (#{0 1} exit)
                      (throw (ex-info "Discovery compiler did not complete normally"
                                      {:aguafria/phase :namespace-discovery
                                       :namespace namespace :exit exit})))
                    (if (= 1 exit)
                      (assoc analysis :status :rejected :reason :compiler-rejected-namespace)
                      (discovery/prepare-observations! analysis))))]
    (with-open [executor (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))]
      (let [jobs (mapv (fn [{:keys [namespace status configuration] :as loaded}]
                         (.submit executor
                                  ^Callable
                                  (bound-fn []
                                    (let [report (if status (dissoc loaded :configuration)
                                                     (try (binding [bundle/*preparation-owner* namespace]
                                                            (runtime/call-with-precompile-configuration
                                                             configuration #(prepare namespace)))
                                                          (catch Exception error
                                                            (when (= :namespace-discovery (:aguafria/phase (ex-data error)))
                                                              (throw error))
                                                            (assoc (discovery/error-report error)
                                                                   :namespace namespace :status :analysis-failed))))
                                          checkpoint (io/file (str report-file ".d")
                                                              (str namespace ".edn"))]
                                      (io/make-parents checkpoint)
                                      (spit checkpoint (artifact/print-data report))
                                      report)))) loads)]
        (mapv #(.get ^java.util.concurrent.Future %) jobs)))))

(defn- admit-namespaces!
  "Admission is based on compiler results, never a namespace allow/deny list.
  Failed source discovery is excluded before normal-context codegen validation.
  A shared artifact retains every requester so exclusion cannot strand a survivor."
  [report]
  (let [collected bundle/*preparing*
        initial-reasons
        (merge
         (into {} (keep #(when (= :failed (:status %))
                           [(:namespace %) :ordinary-jvm-planning]))
               (:namespace-images report))
         (into {} (keep #(when-let [reason ({:rejected :source-discovery
                                             :load-failed :namespace-load
                                             :analysis-failed :discovery-planning
                                             :failed :discovery-planning} (:status %))]
                           [(:namespace %) reason]))
               (:analysis report)))
        initially-rejected (set (keys initial-reasons))
        _ (bundle/exclude-preparation-owners! collected initially-rejected)
        validation (binding [compiler-work/*phase* :namespace-admission]
                     (runtime/validate-precompile-bundles!
                      collected {:full-build? true :retain-pending? true}))
        rejected (into initially-rejected (bundle/rejected-preparation-owners collected))
        _ (bundle/exclude-preparation-owners! collected rejected)
        owner (fn [record]
                (or (:namespace record)
                    (some-> (:function record) namespace symbol)))
        rejected-record? #(contains? rejected (owner %))
        marker (fn [record]
                 (assoc (select-keys record [:namespace :function :args :type :basis :baseline])
                        :status :rejected :reason :compiler-rejected-namespace))
        fields [:analysis :namespace-images :functions :calls]
        rejected-records (into {}
                               (for [field fields]
                                 [field (filterv rejected-record? (get report field))]))
        state @collected]
    {:report (reduce (fn [report field]
                       (update report field
                               #(mapv (fn [record]
                                        (if (rejected-record? record) (marker record) record)) %)))
                     report fields)
     :rejected-records rejected-records
     :admission
     {:validation validation
      :rejected-namespaces
      (mapv (fn [namespace]
              {:namespace namespace :status :rejected
               :reason (get initial-reasons namespace :ordinary-jvm-compilation)
               :artifact-errors
               (into {}
                     (for [[id result] (:validation-results state)
                           :when (and (= :failed (:status result))
                                      (contains? (get-in state [:artifact-owners id]) namespace))]
                       [id result]))})
            (sort-by str rejected))}}))

(defn- prepare-scalar-profiles! [initial-configuration]
  (let [types (vec (sort-by artifact/print-data @runtime/*prepared-scalar-constructors*))
        profiles (vec (distinct (concat [initial-configuration (runtime/configuration)]
                                        (keep :configuration
                                              (vals @runtime/*prepared-namespace-images*)))))
        prepare (requiring-resolve 'aguafria.zig.jvm/precompile-coercion!)
        results (mapv (fn [[profile type]]
                        (try
                          (assoc (runtime/call-with-precompile-configuration
                                  (nth profiles profile)
                                  #(bundle/call-with-validation-scope (fn [] (prepare type))))
                                 :profile profile)
                          (catch Exception error
                            (assoc (discovery/error-report error)
                                   :status :failed :type type :profile profile))))
                      (for [profile (range (count profiles)) type types] [profile type]))]
    {:types types :profiles (count profiles)
     :statuses (frequencies (map :status results)) :constructors results}))

(defn coverage
  "Summarize the emitted-operation inventory without treating observation,
  partial preparation, or skipped work as successful preparation. This is not
  a claim to have evaluated every source form or instantiated every generic."
  [analysis]
  (let [functions (mapcat :functions analysis)
        constant-readers (mapcat :constant-readers analysis)
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
     :constant-readers {:total (count constant-readers)
                        :statuses (frequencies (map :status constant-readers))
                        :skip-reasons (frequencies (keep :reason constant-readers))}
     :declared-initializer-operations
     (let [initializers (filter #(= :declared-constant (:execution-plan %)) operations)]
       {:total (count initializers)
        :owner-handlers (frequencies (map :status (mapcat :handlers initializers)))
        :independent-call-handlers
        (frequencies (map :status (mapcat :independent-call-handlers initializers)))})
     :gaps (frequencies
            (concat (keep #(when-not (= :observed (:status %))
                             (or (:reason %) (:status %))) operations)
                    (keep #(when-not (= :prepared (:status %))
                             (or (:reason %) (:status %))) handlers)))}))

(defn- bundle-failure! [report-file report started error]
  (let [failed (assoc report
                      :compiler-work (compiler-work/report compiler-work/*collector*)
                      :bundles (merge (discovery/error-report error)
                                      (select-keys (ex-data error)
                                                   [:aguafria/phase :reason :exit
                                                    :command :out :err :configurations])
                                      {:status :failed})
                      :duration-ms (/ (- (System/nanoTime) started) 1e6))]
    (io/make-parents report-file)
    (spit report-file (artifact/print-data failed))
    (throw (ex-info (ex-message error)
                    (assoc (ex-data error) :report-file report-file) error))))

(defn precompile!
  "Compile persistent JVM artifacts explicitly, never as part of :prepare.

  :namespaces compiles concrete native functions without calling their bodies.
  Their native input/result constructors are included in preparation.
  Validated scalar constructors also cover the captured namespace-load profiles,
  so ordinary calls retain scalar results before and after native links are added.
  Generic functions, comptime results, externs, tests and process entry hosts
  are reported as skipped, not executed to discover their signatures.
  Initial images for ordinary namespace loading are prepared as well and
  reported in :namespace-images, including failures and lazy imports.
  Native test artifacts are not built here: calling an a/deftest uses the existing
  native test compiler/runner. Test bodies still participate in :analyze so their
  ordinary JVM handlers are prepared for evaluating those forms outside a native
  test. This does not execute tests or change ordinary test registration/execution.

  :calls supplies exact native argument types for builtin/operator/imported or
  generic call adapters. Use {:comptime value} for source-level comptime inputs.
  No runtime call is made. Unsupported adapter paths fail explicitly.

  :coercions lists native schemas for value constructors, including the storage
  adapters that keep numeric results addressable on the JVM.

  :analyze lists namespaces whose emitted operations are inspected by Zig and
  used to prepare supported handlers automatically. It also prepares their
  concrete top-level callables and reports each unsupported or failed function
  separately, including functions not called by another example form.
  Lazy constant readers and their cleanup use the ordinary demand plan without
  reading values; their preparation is reported separately as :constant-readers.
  A complete declared constant initializer retains that reader as its owning
  comptime plan. Its separately attempted standalone call handlers remain in
  :independent-call-handlers; owner preparation does not make them runtime calls.
  :source-dirs discovers all
  .clj namespaces in directories already on the classpath. Per-operation gaps
  and compiler errors remain in :analysis. :parallelism defaults to 2; analysis
  and frozen namespace-image builds run on bounded virtual-thread
  workers. Inputs are captured during sequential loading, with at most twice that many
  outstanding builds. Every result is resolved before reporting preparation.
  Reports persist under :report-file
  (default .aguafria/precompile/report.edn).
  :compiler-work counts actual compiler attempts across loading, type queries,
  native tests, support/tools and bundling, including rejected builds. Its
  :one-compilation? describes the whole preparation; :bundles counts only the
  final pack. Command samples are capped at 32; totals remain exact.

  :ignore is a collection of namespace symbols excluded from explicit function,
  call and source-directory analysis requests, before loading them. The report's
  :ignored entries are not counted as prepared. This does not suppress imports
  transitively required by another selected namespace or change normal execution.

  Compatible generated JVM handlers, including cached ones, are compiled into
  one immutable shared-cache library. Incompatible configurations fail instead
  of silently splitting the bundle.
  Analyzed namespaces with compiler errors are rejected automatically, without
  a namespace deny list. Remaining exact ordinary graphs undergo bounded native
  codegen/link admission; a rejected graph excludes its requesting namespaces.
  Rejections and their original discovery reports are retained separately and
  never counted as prepared. Shared artifacts stay when surviving requests need
  them. This admission uses additional, fully counted compiler commands.
  Initial namespace images retain their frozen sources and compiler options.
  The final bundle build validates and links the admitted graph before publication.
  An unexpected final-build failure still aborts; it cannot publish pending code.
  Application handlers are never loaded
  or invoked during preparation; the bounded native tokenizer analyzes source.
  Handlers with unsupported bundle configurations fail during planning instead
  of compiling standalone libraries.
  A bundle failure preserves the completed analysis/coverage in :report-file,
  records :bundles :status :failed, and still throws the compiler exception.

  Namespace loading still runs ordinary Clojure top-level code and macros; Zig
  performs its normal comptime analysis. No function/test/comment body is run.
  Preparation retains each namespace's load-time compiler configuration,
  including transitive imports, so later link settings cannot change its keys.
  The normal cache invalidation rules apply; handles and state are not saved."
  [{:keys [namespaces calls coercions analyze source-dirs parallelism report-file ignore]
    :or {parallelism 2 report-file ".aguafria/precompile/report.edn"}
    :as options}]
  (validate-options! options)
  (with-open [build-executor
              (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))]
    (binding [runtime/*compile-only?* true
              compiler-work/*collector* (compiler-work/collector)
              runtime/*precompile-build-submitter* (build-submitter build-executor parallelism)
              runtime/*prepared-namespace-images* (atom {})
              runtime/*prepared-scalar-constructors* (atom #{})
              artifact/*source-key-cache* (artifact/source-key-cache)
              bundle/*batch-validation?* true
              bundle/*preparing* (atom {})]
      (let [started (System/nanoTime)
            initial-configuration (runtime/configuration)
            excluded (set ignore)
            analysis-namespaces (vec (distinct (concat analyze (source-namespaces source-dirs))))
            call-namespace (comp symbol namespace :function)
            selected (distinct (concat namespaces analysis-namespaces (map call-namespace calls)))
            ignored (mapv (fn [namespace]
                            {:namespace namespace :status :ignored :reason :explicit-ignore})
                          (filter excluded selected))
            namespaces (remove excluded namespaces)
            calls (remove #(excluded (call-namespace %)) calls)
            analysis-namespaces (filterv #(not (excluded %)) analysis-namespaces)
            images (atom {})
            configurations (atom {})]
        (doseq [namespace (distinct (concat namespaces
                                            (map (comp symbol namespace :function) calls)))]
          (swap! configurations assoc namespace (load-namespace! namespace images)))
        (resolve-precompile-images! images)
        (let [prepare-in (fn [namespace prepare]
                           (binding [bundle/*preparation-owner* namespace]
                             (runtime/call-with-precompile-configuration
                              (get @configurations namespace) prepare)))
              report {:functions (into [] (mapcat (fn [namespace]
                                                    (prepare-in namespace
                                                                #(runtime/precompile-functions! namespace))))
                                       (distinct namespaces))
                      :calls (mapv (fn [call]
                                     (prepare-in (call-namespace call)
                                                 #(bundle/call-with-validation-scope
                                                   (fn [] ((requiring-resolve 'aguafria.zig.jvm/precompile-call!) call)))))
                                   calls)
                      :coercions (mapv (fn [type]
                                         (bundle/call-with-validation-scope
                                          #((requiring-resolve 'aguafria.zig.jvm/precompile-coercion!) type))) coercions)
                      :analysis (analyze-namespaces! analysis-namespaces parallelism report-file images)
                      :ignored ignored
                      :cache-dir (:cache-dir (runtime/configuration))
                      :duration-ms (/ (- (System/nanoTime) started) 1e6)}
              scalar-profiles (prepare-scalar-profiles! initial-configuration)
              _ (resolve-precompile-images! images)
              report (assoc report :scalar-constructor-profiles scalar-profiles
                            :namespace-images (mapv val (sort-by key @images))
                            :coverage (-> (coverage (:analysis report))
                                          (assoc-in [:namespaces :ignored] (count ignored))
                                          (assoc :namespace-images (frequencies (map :status (vals @images)))
                                                 :scalar-constructor-profiles (:statuses scalar-profiles)))
                            :duration-ms (/ (- (System/nanoTime) started) 1e6))
              admission (when (seq (:analysis report))
                          (try (admit-namespaces! report)
                               (catch Exception error
                                 (bundle-failure! report-file report started error))))
              report (or (:report admission) report)
              compiled-bundles
              (try
                (binding [compiler-work/*phase* :handler-bundle]
                  (runtime/finish-precompile-bundles! bundle/*preparing*
                                                      {:validate-pending? true}))
                (catch Exception error
                  (bundle-failure! report-file report started error)))
              report (try
                       (bundle/resolve-validation-report!
                        bundle/*preparing* report discovery/error-report)
                       (catch Exception error (bundle-failure! report-file report started error)))
              scalar-statuses (frequencies (map :status
                                                (get-in report [:scalar-constructor-profiles :constructors])))
              report (-> report
                         (assoc-in [:scalar-constructor-profiles :statuses] scalar-statuses)
                         (update :coverage merge (coverage (:analysis report)))
                         (assoc-in [:coverage :namespaces :ignored] (count ignored))
                         (assoc-in [:coverage :namespace-images]
                                   (frequencies (map :status (:namespace-images report))))
                         (assoc-in [:coverage :scalar-constructor-profiles] scalar-statuses))
              report (cond-> report
                       admission (assoc :namespace-admission (:admission admission)
                                        :rejected-preparation (:rejected-records admission)))
              report (assoc report :bundles compiled-bundles
                            :compiler-work (compiler-work/report compiler-work/*collector*)
                            :source-key-cache (artifact/source-key-statistics artifact/*source-key-cache*)
                            :duration-ms (/ (- (System/nanoTime) started) 1e6))]
          (io/make-parents report-file)
          (spit report-file (artifact/print-data report))
          report)))))
