(ns aguafria.benchmark
  "Reproducible local performance probes for Aguafria's development path.

  This is deliberately separate from `aguafria.zig`: benchmarking is tooling,
  not a declaration primitive. Results are plain EDN and include enough host
  metadata to compare runs honestly."
  (:require [aguafria.zig :as az]
            [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util ArrayList]))

(defn- elapsed-ms [started-nanos]
  (/ (double (- (System/nanoTime) started-nanos)) 1000000.0))

(defn- percentile [sorted-values p]
  (when (seq sorted-values)
    (let [index (-> (* (double p) (count sorted-values))
                    Math/ceil long dec
                    (max 0)
                    (min (dec (count sorted-values))))]
      (nth sorted-values index))))

(defn- distribution [values]
  (let [values (vec (sort values))]
    {:samples (count values)
     :min-ns (first values)
     :p50-ns (percentile values 0.50)
     :p95-ns (percentile values 0.95)
     :p99-ns (percentile values 0.99)
     :max-ns (peek values)
     :mean-ns (when (seq values)
                (/ (double (reduce + values)) (count values)))}))

(defn- measure [f warmups samples]
  (dotimes [_ warmups] (f))
  (distribution
   (repeatedly samples
               (fn []
                 (let [started (System/nanoTime)]
                   (f)
                   (- (System/nanoTime) started))))))

(defn- compile-measurement [module wall-ms]
  (let [build (:last-build (az/stats module))]
    {:wall-ms wall-ms
     :native-build-ms (:duration-ms build)
     :cached? (boolean (:cached? build))
     :generation (:generation build)
     :hash (:hash build)}))

(defn- machine-info
  [zig-version]
  {:os (System/getProperty "os.name")
   :os-version (System/getProperty "os.version")
   :arch (System/getProperty "os.arch")
   :processors (.availableProcessors (Runtime/getRuntime))
   :java-version (System/getProperty "java.version")
   :clojure-version (clojure-version)
   :zig-version zig-version})

(defn- compile-fixture-group!
  [async? module-count]
  (let [old-config (az/configuration)
        suffix (random-uuid)
        symbols (mapv #(symbol (str "aguafria.benchmark-"
                                    (if async? "parallel-" "serial-")
                                    suffix "-" %))
                      (range module-count))
        namespaces (mapv create-ns symbols)]
    (try
      (az/configure! {:async? async?
                      :reloadable? true
                      :optimize "ReleaseFast"
                      :modules {}
                      :zig-args []})
      (doseq [target namespaces]
        (binding [*ns* target]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)))
      (let [started (System/nanoTime)]
        (doseq [target namespaces]
          (binding [*ns* target]
            (eval '(az/defn fixture_value :- :u64 [] 42))))
        (doseq [module symbols]
          (az/await! module))
        (let [builds (mapv #(get-in (az/stats %) [:last-build]) symbols)
              durations (keep :duration-ms builds)]
          {:module-count module-count
           :async? async?
           :wall-ms (elapsed-ms started)
           :native-build-sum-ms (reduce + 0 durations)
           :native-critical-path-ms (reduce max 0 durations)
           :cache-hit-count (count (filter :cached? builds))}))
      (finally
        (az/configure! old-config)
        (doseq [module symbols]
          (remove-ns module))))))

(defn- invoke-u64
  [^MethodHandle handle value]
  (.invokeWithArguments handle
                        (ArrayList. ^java.util.Collection [(long value)])))

(defn- final-artifact-benchmark!
  [iterations samples]
  (let [old-config (az/configuration)
        module-symbol (symbol (str "aguafria.final-benchmark-" (random-uuid)))
        module-ns (create-ns module-symbol)
        output-directory
        (.toFile (Files/createTempDirectory
                  "aguafria-final-benchmark-"
                  (make-array FileAttribute 0)))
        output (java.io.File. output-directory
                              (System/mapLibraryName "aguafria_final_benchmark"))]
    (try
      (az/configure! {:async? false
                      :reloadable? false
                      :optimize "ReleaseFast"
                      :modules {}
                      :zig-args []})
      (binding [*ns* module-ns]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (eval '(az/defn step :- :u64 [x :- :u64] (+ x 1)))
        (eval
         '(az/defn direct_loop :- :u64 [n :- :u64]
            (var total :u64 0)
            (var i :u64 0)
            (while (< i n)
              (+= total (+ i 1))
              (+= i 1))
            total))
        (eval
         '(az/defn composed_loop :- :u64 [n :- :u64]
            (var total :u64 0)
            (var i :u64 0)
            (while (< i n)
              (+= total (step i))
              (+= i 1))
            total)))
      (let [source (az/source module-symbol)
            build (az/build! module-symbol
                             {:kind :dynamic-lib
                              :output (.getAbsolutePath output)
                              :optimize "ReleaseFast"})]
        (with-open [arena (Arena/ofConfined)]
          (let [linker (Linker/nativeLinker)
                lookup (SymbolLookup/libraryLookup (.toPath output) arena)
                descriptor
                (FunctionDescriptor/of ValueLayout/JAVA_LONG
                                       (into-array MemoryLayout
                                                   [ValueLayout/JAVA_LONG]))
                options (into-array Linker$Option [])
                handle
                (fn [name]
                  (.downcallHandle
                   linker
                   (-> (.find lookup name)
                       (.orElseThrow))
                   descriptor options))
                direct-handle (handle "direct_loop")
                composed-handle (handle "composed_loop")
                expected (invoke-u64 direct-handle iterations)
                actual (invoke-u64 composed-handle iterations)
                _ (when-not (= expected actual)
                    (throw (ex-info "Final direct/composed results differ"
                                    {:expected expected :actual actual})))
                direct (measure #(invoke-u64 direct-handle iterations) 3 samples)
                composed (measure #(invoke-u64 composed-handle iterations) 3 samples)]
            {:artifact-path (.getAbsolutePath output)
             :artifact-bytes (.length output)
             :native-build-ms (:duration-ms build)
             :dispatch-markers? (boolean
                                 (re-find #"__aguafria_(?:dispatch|state|epoch)"
                                          source))
             :direct direct
             :composed composed
             :composed-to-direct-p50-ratio
             (/ (double (:p50-ns composed)) (:p50-ns direct))})))
      (finally
        (az/configure! old-config)
        (remove-ns module-symbol)))))

(def ^:private project-specs
  {:sample {:input "sample"
            :namespace-prefix 'aguafria.benchmark.sample
            :options {}}
   :tigerbeetle {:input "vendor/tigerbeetle"
                 :namespace-prefix 'aguafria.benchmark.tigerbeetle
                 :options {:build-profiles [[] ["vopr"]]}}})

(defn- project-conversion-summary
  [report wall-ms]
  {:wall-ms wall-ms
   :reported-ms (:elapsed-ms report)
   :file-count (:file-count report)
   :ast-cache-hit-count (:ast-cache-hit-count report)
   :conversion-cache-hit-count (:conversion-cache-hit-count report)
   :declaration-count (:declaration-count report)
   :fallback-count (:fallback-count report)
   :unresolved-syntax-count (:unresolved-syntax-count report)
   :written-file-count (count (filter :written? (:files report)))})

(defn benchmark-project!
  "Benchmark conversion, EDN catalog bootstrap, Var loading, and emission.

  `project` is `:sample` or `:tigerbeetle`. Output is written only to a new
  operating-system temporary directory, never to the checked-in generated
  corpus. Returns plain EDN with first/repeat phase timings."
  [project]
  (let [{:keys [input namespace-prefix options] :as spec}
        (get project-specs project)]
    (when-not spec
      (throw (ex-info "Unknown Aguafria benchmark project"
                      {:project project :known (sort (keys project-specs))})))
    (let [output (.toFile
                  (Files/createTempDirectory
                   (str "aguafria-benchmark-" (name project) "-")
                   (make-array FileAttribute 0)))
          conversion-options
          (merge {:namespace-prefix namespace-prefix
                  :overwrite? true
                  :bundle-assets? false}
                 options)
          first-started (System/nanoTime)
          first-report (convert/convert-tree! input output conversion-options)
          first-conversion (project-conversion-summary
                            first-report (elapsed-ms first-started))
          repeat-started (System/nanoTime)
          repeat-report (convert/convert-tree! input output conversion-options)
          repeat-conversion (project-conversion-summary
                             repeat-report (elapsed-ms repeat-started))
          catalog-already-loaded? (boolean (find-ns 'aguafria.std))
          catalog-started (System/nanoTime)
          _ (require 'aguafria.std)
          catalog-cold-ms (elapsed-ms catalog-started)
          install-all! (requiring-resolve 'aguafria.zig.std/install-all!)
          catalog-warm-started (System/nanoTime)
          warm-install (install-all!)
          catalog-warm-ms (elapsed-ms catalog-warm-started)
          load-started (System/nanoTime)
          first-load (convert/load-tree! output {:compile? false})
          first-load-ms (elapsed-ms load-started)
          namespaces (mapv :namespace (:files first-report))
          emit-started (System/nanoTime)
          emitted-sources
          (mapv (fn [module]
                  (emitter/emit-module
                   (str module)
                   (:definitions (az/module-info module))))
                namespaces)
          emission-ms (elapsed-ms emit-started)
          repeat-load-started (System/nanoTime)
          repeat-load (convert/load-tree! output {:compile? false})
          repeat-load-ms (elapsed-ms repeat-load-started)]
      {:benchmark :aguafria/project-pipeline
       :recorded-at-ms (System/currentTimeMillis)
       :machine (machine-info (or (:zig-version first-report)
                                  (get-in first-report
                                          [:files 0 :zig-version])))
       :project project
       :input-root (:input-root first-report)
       :temporary-output-root (.getAbsolutePath output)
       :conversion {:first first-conversion
                    :repeat repeat-conversion}
       :catalog {:already-loaded? catalog-already-loaded?
                 :cold-load-and-install-ms catalog-cold-ms
                 :warm-install-ms catalog-warm-ms
                 :namespace-count (:namespace-count warm-install)
                 :var-count (:var-count warm-install)}
       :namespace-load {:first-wall-ms first-load-ms
                        :repeat-wall-ms repeat-load-ms
                        :file-count (:file-count first-load)
                        :declaration-count (:declaration-count first-load)
                        :repeat-declaration-count
                        (:declaration-count repeat-load)}
       :emission {:wall-ms emission-ms
                  :module-count (count emitted-sources)
                  :source-bytes (reduce + 0 (map count emitted-sources))}})))

(defn benchmark!
  "Run native development-path probes and return a serializable EDN map.

  Options:
  - `:iterations` native loop iterations (default 1,000,000)
  - `:samples` timed samples after warmup (default 7)
  - `:ffm-calls` scalar JVM→native calls per timed sample (default 10,000)
  - `:parallel-modules` independent compiler scheduling probes (default 4)"
  ([] (benchmark! {}))
  ([{:keys [iterations samples ffm-calls parallel-modules]
     :or {iterations 1000000 samples 7 ffm-calls 10000
          parallel-modules 4}}]
   (doseq [[option value] [[:iterations iterations]
                           [:samples samples]
                           [:ffm-calls ffm-calls]
                           [:parallel-modules parallel-modules]]]
     (when-not (and (integer? value) (pos? value))
       (throw (ex-info "Benchmark options must be positive integers"
                       {:option option :value value}))))
   (let [old-config (az/configuration)
         module-symbol (symbol (str "aguafria.benchmark-fixture-"
                                    (random-uuid)))
         module-ns (create-ns module-symbol)]
     (try
       (az/configure! {:async? false
                       :reloadable? true
                       :optimize "ReleaseFast"})
       (binding [*ns* module-ns]
         (refer 'clojure.core)
         (alias 'az 'aguafria.zig))
       (let [clean-started (System/nanoTime)
             _ (binding [*ns* module-ns]
                 (eval
                  '(az/defn hot-step
                     {:attrs #{:public :implicit-return}}
                     :- :u64
                     [x :- :u64]
                     (+ x 1)))
                 (eval
                  '(az/defn direct-loop :- :u64
                     [n :- :u64]
                     (var total :u64 0)
                     (var i :u64 0)
                     (while (< i n)
                       (+= total (+ i 1))
                       (+= i 1))
                     total))
                 (eval
                  '(az/defn dispatch-loop :- :u64
                     [n :- :u64]
                     (var total :u64 0)
                     (var i :u64 0)
                     (while (< i n)
                       (+= total (hot-step i))
                       (+= i 1))
                     total)))
             clean (compile-measurement module-symbol
                                        (elapsed-ms clean-started))
             cached-started (System/nanoTime)
             _ (az/recompile! module-symbol)
             cached (compile-measurement module-symbol
                                         (elapsed-ms cached-started))
             incremental-started (System/nanoTime)
             _ (binding [*ns* module-ns]
                 (eval
                  '(az/defn hot-step
                     {:attrs #{:public :implicit-return}}
                     :- :u64
                     [x :- :u64]
                     (+ x 2))))
             incremental (compile-measurement module-symbol
                                              (elapsed-ms incremental-started))
             _ (binding [*ns* module-ns]
                 (eval
                  '(az/defn hot-step
                     {:attrs #{:public :implicit-return}}
                     :- :u64
                     [x :- :u64]
                     (+ x 1))))
             direct-loop (ns-resolve module-ns 'direct-loop)
             dispatch-loop (ns-resolve module-ns 'dispatch-loop)
             expected (direct-loop iterations)
             actual (dispatch-loop iterations)
             _ (when-not (= expected actual)
                 (throw (ex-info "Benchmark direct/dispatch results differ"
                                 {:expected expected :actual actual})))
             direct (measure #(direct-loop iterations) 3 samples)
             dispatch (measure #(dispatch-loop iterations) 3 samples)
             ffm (measure #(dotimes [_ ffm-calls] (direct-loop 1)) 2 samples)
             direct-median (:p50-ns direct)
             dispatch-median (:p50-ns dispatch)
             stats (az/stats module-symbol)
             final-artifact (final-artifact-benchmark! iterations samples)
             serial-compilation (compile-fixture-group! false parallel-modules)
             parallel-compilation (compile-fixture-group! true parallel-modules)]
         {:benchmark :aguafria/development-native
          :recorded-at-ms (System/currentTimeMillis)
          :machine (machine-info (get-in stats [:last-build :zig-version]))
          :parameters {:iterations iterations
                       :samples samples
                       :ffm-calls ffm-calls
                       :parallel-modules parallel-modules
                       :optimize "ReleaseFast"}
          :compilation {:clean clean
                        :cached cached
                        :incremental incremental}
          :native-loop {:direct direct
                        :dispatch dispatch
                        :dispatch-p50-ns-per-iteration
                        (/ (double dispatch-median) iterations)
                        :dispatch-to-direct-p50-ratio
                        (when (pos? (long direct-median))
                          (/ (double dispatch-median) direct-median))}
          :ffm {:calls-per-sample ffm-calls
                :batch ffm
                :p50-ns-per-call (/ (double (:p50-ns ffm)) ffm-calls)}
          :compiler-scheduling {:serial serial-compilation
                                :parallel parallel-compilation
                                :serial-to-parallel-wall-ratio
                                (/ (double (:wall-ms serial-compilation))
                                   (:wall-ms parallel-compilation))}
          :final-releasefast final-artifact
          :stats-summary (select-keys stats
                                      [:declaration-count
                                       :native-generation-count
                                       :retired-generation-count
                                       :dispatch-version-count
                                       :timings])})
       (finally
         (az/configure! old-config)
         (remove-ns module-symbol))))))

(defn- micro-options
  [[iterations samples ffm-calls parallel-modules]]
  (cond-> {}
    iterations (assoc :iterations (parse-long iterations))
    samples (assoc :samples (parse-long samples))
    ffm-calls (assoc :ffm-calls (parse-long ffm-calls))
    parallel-modules (assoc :parallel-modules (parse-long parallel-modules))))

(defn- performance-budgets
  []
  (if-let [resource (io/resource "aguafria/performance_budgets.edn")]
    (edn/read-string (slurp resource))
    (throw (ex-info "Aguafria performance budget resource is missing"
                    {:resource "aguafria/performance_budgets.edn"}))))

(defn- assess-budget
  [kind result]
  (let [checks
        (mapv
         (fn [{:keys [name path min max] :as budget}]
           (let [actual (get-in result path ::missing)
                 passed?
                 (and (not= ::missing actual)
                      (or (not (contains? budget :equals))
                          (= (:equals budget) actual))
                      (or (nil? min)
                          (and (number? actual) (<= (double min) actual)))
                      (or (nil? max)
                          (and (number? actual) (<= actual (double max)))))]
             (cond-> {:name name :path path :actual actual :passed? passed?}
               (contains? budget :equals) (assoc :equals (:equals budget))
               min (assoc :min min)
               max (assoc :max max))))
         (get (performance-budgets) kind))
        failures (filterv (comp not :passed?) checks)]
    {:kind kind
     :passed? (empty? failures)
     :check-count (count checks)
     :failure-count (count failures)
     :failures failures
     :checks checks}))

(defn- checked-benchmark!
  [kind]
  (if (= :all kind)
    (let [micro (checked-benchmark! :micro)
          sample (checked-benchmark! :sample)
          tigerbeetle (checked-benchmark! :tigerbeetle)
          passed? (every? #(get-in % [:budget :passed?])
                          [micro sample tigerbeetle])]
      {:benchmark :aguafria/all
       :micro micro :sample sample :tigerbeetle tigerbeetle
       :budget {:kind :all :passed? passed?}})
    (let [result (case kind
                   :micro (benchmark! {})
                   :sample (benchmark-project! :sample)
                   :tigerbeetle (benchmark-project! :tigerbeetle)
                   (throw (ex-info "Unknown performance budget target"
                                   {:target kind
                                    :known [:micro :sample :tigerbeetle :all]})))]
      (assoc result :budget (assess-budget kind result)))))

(defn -main [& arguments]
  (try
    (let [[mode & arguments] arguments
          result
          (case mode
            "check"
            (checked-benchmark! (keyword (or (first arguments) "micro")))

         "project"
         (benchmark-project! (some-> (first arguments) keyword))

         "all"
         {:benchmark :aguafria/all
          :micro (benchmark! {})
          :sample (benchmark-project! :sample)
          :tigerbeetle (benchmark-project! :tigerbeetle)}

         "micro"
         (benchmark! (micro-options arguments))

         ;; Preserve the original concise numeric invocation:
         ;; `clojure -M:bench 100000 5 2000 4`.
         (benchmark! (micro-options (cond-> arguments mode (conj mode)))))]
      (prn result)
      (when (false? (get-in result [:budget :passed?]))
        (throw (ex-info "Aguafria performance budget failed"
                        {:budget (:budget result)}))))
    (finally
      (shutdown-agents))))
