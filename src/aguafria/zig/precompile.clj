(ns aguafria.zig.precompile
  "Explicit, compile-only preparation of persistent native artifacts."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.compiler-work :as compiler-work]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable Executors Semaphore TimeUnit]))

(defn- progress! [& parts]
  (locking *err*
    (binding [*out* *err*]
      (apply println parts)
      (flush))))

(defn- elapsed-ms [started]
  (/ (- (System/nanoTime) started) 1e6))

(defn- seconds [milliseconds]
  (format "%.2fs" (/ milliseconds 1000.0)))

(defn- timed! [timings phase f]
  (let [started (System/nanoTime)]
    (try (f)
         (finally (swap! timings assoc phase (elapsed-ms started))))))

(defn- with-heartbeat [report f]
  (with-open [timer (Executors/newSingleThreadScheduledExecutor (.factory (Thread/ofVirtual)))]
    (let [job (.scheduleAtFixedRate timer ^Runnable (bound-fn [] (report)) 5 5 TimeUnit/SECONDS)]
      (try (f) (finally (.cancel job false))))))

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
                 (every? #{:namespaces :calls :coercions :analyze :tests :source-dirs :parallelism :report-file :ignore :linkage-contexts} (keys options))
                 (or (nil? (:ignore options)) (sequential? (:ignore options)) (set? (:ignore options)))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:ignore options))
                 (every? sequential? (vals (select-keys options [:namespaces :calls :coercions :analyze :tests :source-dirs :linkage-contexts])))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:namespaces options))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:analyze options))
                 (every? qualified-symbol? (:tests options))
                 (every? #(and (symbol? %) (nil? (namespace %))) (:linkage-contexts options))
                 (every? string? (:source-dirs options))
                 (or (nil? (:parallelism options))
                     (pos-int? (:parallelism options)))
                 (or (nil? (:report-file options)) (string? (:report-file options)))
                 (every? #(and (map? %)
                               (= #{:function :args} (set (keys %)))
                               (qualified-symbol? (:function %))
                               (vector? (:args %))) (:calls options))
                 (or (seq (:namespaces options)) (seq (:calls options))
                     (seq (:coercions options)) (seq (:analyze options))
                     (seq (:source-dirs options)) (seq (:tests options))))
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

(defn- previous-durations [report-file]
  ;; Scheduling hints only: stale timings never authorize artifact reuse or
  ;; skip work. Keep the caller's report order independent of execution order.
  (if (.isFile (io/file report-file))
    (try
      (with-open [reader (java.io.PushbackReader. (io/reader report-file))]
        (into {} (keep (fn [{:keys [namespace timings]}]
                         (when (number? (:duration-ms timings))
                           [namespace (:duration-ms timings)])))
              (:analysis (edn/read reader))))
      (catch Exception _ {}))
    {}))

(defn- analyze-namespaces!
  ([namespaces parallelism report-file images]
   (analyze-namespaces! namespaces parallelism report-file images (atom {})))
  ([namespaces parallelism report-file images timings]
   ;; Namespace initialization runs sequentially; compiler work uses virtual workers.
   (let [total (count namespaces)
         previous (if (seq namespaces) (previous-durations report-file) {})
         completed (atom 0)
         active (atom {})
         heartbeat (fn [phase]
                     (let [running @active]
                       (progress! phase (str @completed "/" total) "completed;"
                                  (count running) "active;"
                                  (str/join "; "
                                            (for [[namespace {:keys [started stage]}]
                                                  (take 3 (sort-by (comp :started val) running))]
                                              (str namespace " " (name stage) " " (seconds (elapsed-ms started))))))))
         _ (when (pos? total) (progress! "Loading" (str "0/" total)))
         loads
         (timed! timings :namespace-loading-ms
                 #(with-heartbeat
                    (fn [] (heartbeat "Loading"))
                    (fn []
                      (mapv (fn [namespace]
                              (let [started (System/nanoTime)
                                    _ (swap! active assoc namespace {:started started :stage :loading})
                                    loaded
                                    (try
                                      (binding [compiler-work/*owner* namespace]
                                        (let [configuration (load-namespace! namespace images)]
                                          (cond-> {:namespace namespace :configuration configuration}
                                            (= :no-native-declarations (get-in @images [(str namespace) :reason]))
                                            (assoc :status :skipped :reason :no-native-declarations))))
                                      (catch Exception error
                                        (assoc (discovery/error-report error) :namespace namespace :status :load-failed))
                                      (finally (swap! active dissoc namespace)))
                                    duration (elapsed-ms started)]
                                (progress! "Loading" (str (swap! completed inc) "/" total) namespace (seconds duration))
                                (assoc loaded :timings {:load-ms duration}))) namespaces))))
         _ (timed! timings :namespace-images-ms
                   #(with-heartbeat (fn [] (progress! "Waiting for namespace image builds..."))
                      (fn [] (resolve-precompile-images! images))))
         _ (reset! completed 0)
         _ (when (pos? total) (progress! "Analyzing" (str "0/" total)))
         _ (when (seq previous) (progress! "Scheduling longest previous analyses first;" parallelism "workers"))
         prepare (fn [namespace measurements]
                   (let [analysis (timed! measurements :discovery-ms #(discovery/analyze! namespace))
                         exit (get-in analysis [:baseline :exit])]
                     (when-not (#{0 1} exit)
                       (throw (ex-info "Discovery compiler did not complete normally"
                                       {:aguafria/phase :namespace-discovery :namespace namespace :exit exit})))
                     (if (= 1 exit)
                       (assoc analysis :status :rejected :reason :compiler-rejected-namespace)
                       (do
                         (swap! active assoc-in [namespace :stage] :preparing)
                         (timed! measurements :preparation-ms #(discovery/prepare-observations! analysis))))))]
     (timed! timings :analysis-ms
             #(with-heartbeat
                (fn [] (heartbeat "Analyzing"))
                (fn []
                  (with-open [executor (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))]
                    (try
                      (let [scheduled (sort-by (fn [loaded] (get previous (:namespace loaded) 0)) > loads)
                            jobs
                            (mapv (fn [{:keys [namespace status configuration] :as loaded}]
                                    (let [queued (System/nanoTime)]
                                      (.submit executor ^Callable
                                               (bound-fn []
                                                 (let [started (System/nanoTime)
                                                       _ (.setName (Thread/currentThread) (str "aguafria-analysis:" namespace))
                                                       measurements (atom (assoc (:timings loaded) :queued-ms (elapsed-ms queued)))
                                                       _ (swap! active assoc namespace {:started started :stage :discovering})]
                                                   (try
                                                     (let [report
                                                           (if status (dissoc loaded :configuration)
                                                               (try
                                                                 (binding [bundle/*preparation-owner* namespace
                                                                           compiler-work/*owner* namespace]
                                                                   (runtime/call-with-precompile-configuration
                                                                    configuration (fn [] (prepare namespace measurements))))
                                                                 (catch Exception error
                                                                   (when (= :namespace-discovery (:aguafria/phase (ex-data error)))
                                                                     (throw error))
                                                                   (assoc (discovery/error-report error)
                                                                          :namespace namespace :status :analysis-failed))))
                                                           duration (elapsed-ms started)
                                                           report (assoc report :timings (assoc @measurements :duration-ms duration))
                                                           checkpoint (io/file (str report-file ".d") (str namespace ".edn"))]
                                                       (io/make-parents checkpoint)
                                                       (spit checkpoint (artifact/print-data report))
                                                       (locking completed
                                                         (progress! "Analyzing" (str (swap! completed inc) "/" total)
                                                                    namespace (seconds duration)
                                                                    (str "(discovery " (seconds (get @measurements :discovery-ms 0))
                                                                         ", preparation " (seconds (get @measurements :preparation-ms 0)) ")")))
                                                       report)
                                                     (finally (swap! active dissoc namespace))))))))
                                  scheduled)
                            jobs (zipmap (map :namespace scheduled) jobs)]
                        (mapv (fn [namespace] (.get ^java.util.concurrent.Future (get jobs namespace))) namespaces))
                      (catch Throwable error
                        (.shutdownNow executor)
                        (throw error))))))))))

(defn- admit-namespaces!
  "Admission is based on compiler results, never a namespace allow/deny list.
  Failed source discovery is excluded before normal-context codegen validation.
  A shared artifact retains every requester so exclusion cannot strand a survivor."
  [report parallelism]
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
                      collected {:full-build? true :retain-pending? true :parallelism parallelism
                                 :progress (fn [{:keys [resolved total active queued failed compiler-batches duration-ms]}]
                                             (progress! "Admission" (str resolved "/" total)
                                                        "resolved;" active "active," queued "queued batches;"
                                                        failed "failed artifacts;" compiler-batches "batches started;"
                                                        (seconds duration-ms)))}))
        rejected (into initially-rejected (bundle/rejected-preparation-owners collected))
        _ (bundle/exclude-preparation-owners! collected rejected)
        owner (fn [record]
                (or (:namespace record)
                    (some-> (:function record) namespace symbol)))
        rejected-record? #(contains? rejected (owner %))
        marker (fn [record]
                 (assoc (select-keys record [:namespace :function :args :type :basis :baseline :timings])
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
  :tests supplies qualified native test names. Each is compiled, never executed,
  with Zig's test frontend and an isolated selected-test table. Namespaced test
  objects are linked into the final AOT image before their indexes are published.
  Test bodies separately participate in :analyze for ordinary JVM body evaluation;
  those observations do not substitute for a requested native test context.

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
  and compiler errors remain in :analysis. :parallelism defaults to all available
  processors and also bounds admission and independent bundle builds; analysis
  and frozen namespace-image builds run on bounded virtual-thread
  workers. Inputs are captured during sequential loading, with at most twice that many
  outstanding builds. Every result is resolved before reporting preparation.
  Loading and analysis emit flushed completed/total progress lines to stderr;
  these count processed namespaces, including failures and skips, not successes.
  Each namespace records :timings for loading, queueing, discovery, preparation
  and worker duration. Report :timings contains phase wall times. Compiler-work
  :command-timings attributes summed command wall times to owners/phases; these
  overlap across workers and are not total elapsed time. Admission retains
  individual batch timings and prints resolved/total, active and queued work.
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
  of silently splitting the bundle. Native process hosts use dedicated images
  so each execution can own and retire its process state independently.
  :linkage-contexts optionally names selected
  namespaces whose authored external/dynamic exports require separate ABI
  linkage contexts. These compile from source through library-mode IR and native
  objects, then link into dedicated immutable AOT images with original symbols.
  This explicit option can publish multiple images and incurs extra compiler
  invocations; it is not a one-library/one-compilation result. It does not add
  namespace selections or waive unsupported compiler/asset restrictions.
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
  [{:keys [namespaces calls coercions analyze tests source-dirs parallelism report-file ignore linkage-contexts]
    :or {parallelism (.availableProcessors (Runtime/getRuntime))
         report-file ".aguafria/precompile/report.edn"}
    :as options}]
  (validate-options! options)
  (with-open [build-executor
              (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))]
    (binding [runtime/*compile-only?* true
              discovery/*native-test-entries* (set (remove #(contains? (set ignore) (symbol (namespace %))) tests))
              compiler-work/*collector* (compiler-work/collector)
              runtime/*precompile-build-submitter* (build-submitter build-executor parallelism)
              runtime/*prepared-namespace-images* (atom {})
              runtime/*prepared-scalar-constructors* (atom #{})
              artifact/*source-key-cache* (artifact/source-key-cache)
              bundle/*isolated-linkage-owners* (set (remove (set ignore) linkage-contexts))
              bundle/*batch-validation?* true
              bundle/*preparing* (atom {})]
      (let [started (System/nanoTime)
            timings (atom {})
            initial-configuration (runtime/configuration)
            excluded (set ignore)
            analysis-namespaces (vec (distinct (concat analyze (source-namespaces source-dirs))))
            call-namespace (comp symbol namespace :function)
            selected (distinct (concat namespaces analysis-namespaces (map call-namespace calls)
                                       (map (comp symbol namespace) tests)))
            ignored (mapv (fn [namespace]
                            {:namespace namespace :status :ignored :reason :explicit-ignore})
                          (filter excluded selected))
            namespaces (remove excluded namespaces)
            calls (remove #(excluded (call-namespace %)) calls)
            tests (vec (distinct (remove #(excluded (symbol (namespace %))) tests)))
            analysis-namespaces (filterv #(not (excluded %)) analysis-namespaces)
            images (atom {})
            configurations (atom {})]
        (timed! timings :explicit-loading-ms
                #(doseq [namespace (distinct (concat namespaces
                                                     (map (comp symbol namespace :function) calls)
                                                     (map (comp symbol namespace) tests)))]
                   (swap! configurations assoc namespace (load-namespace! namespace images))))
        (timed! timings :explicit-images-ms #(resolve-precompile-images! images))
        (let [prepare-in (fn [namespace prepare]
                           (binding [bundle/*preparation-owner* namespace
                                     compiler-work/*owner* namespace]
                             (runtime/call-with-precompile-configuration
                              (get @configurations namespace) prepare)))
              native-tests (timed! timings :native-tests-ms
                                   (fn []
                                     (let [jobs (mapv (fn [test]
                                                        (prepare-in (symbol (namespace test))
                                                                    #(runtime/precompile-test! test))) tests)]
                                       (mapv #(runtime/resolve-precompiled-test! % discovery/error-report) jobs))))
              _ (when (some #(not (#{:pending-publication :prepared} (:status %))) native-tests)
                  (throw (ex-info "Requested native test preparation failed"
                                  {:aguafria/phase :native-test-preparation :tests native-tests})))
              report {:linkage-contexts (vec (remove excluded linkage-contexts))
                      :tests native-tests
                      :functions (timed! timings :functions-ms
                                         (fn [] (into [] (mapcat (fn [namespace]
                                                                   (prepare-in namespace
                                                                               #(runtime/precompile-functions! namespace))))
                                                      (distinct namespaces))))
                      :calls (timed! timings :calls-ms
                                     (fn [] (mapv (fn [call]
                                                    (prepare-in (call-namespace call)
                                                                #(bundle/call-with-validation-scope
                                                                  (fn [] ((requiring-resolve 'aguafria.zig.jvm/precompile-call!) call)))))
                                                  calls)))
                      :coercions (timed! timings :coercions-ms
                                         (fn [] (mapv (fn [type]
                                                        (bundle/call-with-validation-scope
                                                         #((requiring-resolve 'aguafria.zig.jvm/precompile-coercion!) type))) coercions)))
                      :analysis (analyze-namespaces! analysis-namespaces parallelism report-file images timings)
                      :ignored ignored
                      :cache-dir (:cache-dir (runtime/configuration))
                      :duration-ms (/ (- (System/nanoTime) started) 1e6)}
              scalar-profiles (timed! timings :scalar-profiles-ms #(prepare-scalar-profiles! initial-configuration))
              _ (timed! timings :remaining-images-ms #(resolve-precompile-images! images))
              report (assoc report :scalar-constructor-profiles scalar-profiles
                            :namespace-images (mapv val (sort-by key @images))
                            :coverage (-> (coverage (:analysis report))
                                          (assoc-in [:namespaces :ignored] (count ignored))
                                          (assoc :namespace-images (frequencies (map :status (vals @images)))
                                                 :scalar-constructor-profiles (:statuses scalar-profiles)))
                            :duration-ms (/ (- (System/nanoTime) started) 1e6))
              admission (when (seq (:analysis report))
                          (progress! "Validating namespace admission...")
                          (try
                            (timed! timings :admission-ms #(admit-namespaces! report parallelism))
                            (catch Exception error
                              (bundle-failure! report-file (assoc report :timings @timings) started error))))
              report (or (:report admission) report)
              compiled-bundles
              (try
                (progress! "Building bundles...")
                (binding [compiler-work/*phase* :handler-bundle]
                  (timed! timings :bundles-ms
                          #(with-heartbeat
                             (fn [] (progress! "Building bundles..." (seconds (elapsed-ms started)) "total elapsed"))
                             (fn [] (runtime/finish-precompile-bundles! bundle/*preparing*
                                                                        {:validate-pending? true :parallelism parallelism
                                                                         :progress (fn [{:keys [completed total duration-ms]}]
                                                                                     (progress! "Bundles" (str completed "/" total)
                                                                                                (seconds duration-ms)))})))))
                (catch Exception error
                  (bundle-failure! report-file (assoc report :timings @timings) started error)))
              report (try
                       (timed! timings :report-resolution-ms
                               #(with-heartbeat
                                  (fn [] (progress! "Resolving validation diagnostics..."))
                                  (fn [] (bundle/resolve-validation-report!
                                          bundle/*preparing* report discovery/error-report))))
                       (catch Exception error (bundle-failure! report-file (assoc report :timings @timings) started error)))
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
              report (update report :tests
                             (fn [checks]
                               (mapv (fn [check]
                                       (if (= :pending-publication (:status check))
                                         (if-let [entry (bundle/find-artifact
                                                         (:cache-dir (runtime/configuration))
                                                         (:bundle-artifact check))]
                                           (-> check (dissoc :bundle-artifact)
                                               (assoc :status :prepared :bundle-id (:id entry))
                                               (assoc-in [:artifact :library-path] (:library entry)))
                                           (throw (ex-info "Native test was not published" {:test (:test check)})))
                                         (dissoc check :bundle-artifact))) checks)))
              report (assoc report :timings @timings :parallelism parallelism :bundles compiled-bundles
                            :compiler-work (compiler-work/report compiler-work/*collector*)
                            :source-key-cache (artifact/source-key-statistics artifact/*source-key-cache*)
                            :duration-ms (/ (- (System/nanoTime) started) 1e6))]
          (io/make-parents report-file)
          (spit report-file (artifact/print-data report))
          (doseq [[phase duration] (sort-by key @timings)]
            (progress! "Phase" (name phase) (seconds duration)))
          (doseq [entry (take 10 (sort-by #(get-in % [:timings :duration-ms] 0) > (:analysis report)))]
            (progress! "Slow namespace" (:namespace entry) (seconds (get-in entry [:timings :duration-ms] 0))))
          (progress! "Report written:" report-file)
          report)))))
