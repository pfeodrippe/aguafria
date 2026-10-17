(ns aguafria.zig.check
  "Generic, isolated scenario checking. No corpus-specific replay substitutions.
  Each consumer invokes a project-provided entry once in a fresh JVM. The
  producer compiles only; consumers never share application state."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files]
           [java.util UUID]
           [java.util.concurrent Callable Executors TimeUnit]))

(defn- validate-options [options]
  (when (contains? options :execution-modes)
    (throw (ex-info ":execution-modes has been removed; the checker uses native execution"
                    {:option :execution-modes})))
  (let [tests (vec (:tests options))
        scenarios (into (mapv #(hash-map :id % :call % :args [] :expect {:status :passed}) tests)
                        (:scenarios options))
        options (merge {:compilation-modes [:on-demand :prepared]
                        :timeout-seconds 180 :heap-mib 768 :parallelism 1} options)]
    (when (or (empty? scenarios)
              (not= (count scenarios) (count (set (map :id scenarios))))
              (not-every? #(and (:id %) (qualified-symbol? (:call %))
                                (vector? (get % :args [])) (seq (:expect %))
                                (every? #{:value :status :error-name :stdout :stderr :exception}
                                        (keys (:expect %)))) scenarios))
      (throw (ex-info "Provide distinct scenarios with qualified :call, EDN :args, and explicit :expect (or :tests)"
                      {:scenarios scenarios})))
    (doseq [[key allowed] {:compilation-modes #{:on-demand :prepared}}]
      (when-not (and (seq (get options key))
                     (= (count (get options key)) (count (set (get options key))))
                     (every? allowed (get options key)))
        (throw (ex-info "Invalid checker modes" {:option key :value (get options key)}))))
    (when-not (and (pos-int? (:timeout-seconds options)) (pos-int? (:heap-mib options)))
      (throw (ex-info "Checker timeout and heap must be positive integers" {})))
    (when (contains? options :compiler-configuration)
      (when-not (and (map? (:compiler-configuration options))
                     (not-any? #(contains? (:compiler-configuration options) %)
                               [:cache-dir :zig]))
        (throw (ex-info "Checker compiler configuration must be a map and cannot override :cache-dir or :zig"
                        {:compiler-configuration (:compiler-configuration options)}))))
    (when-not (pos-int? (:parallelism options))
      (throw (ex-info "Checker parallelism must be a positive integer" {})))
    (when-let [cache (:prepared-cache-dir options)]
      (when-not (and (string? cache) (.isDirectory (io/file cache))
                     (= [:prepared] (:compilation-modes options)))
        (throw (ex-info "An existing prepared cache requires an existing directory and [:prepared] modes"
                        {:prepared-cache-dir cache}))))
    (doseq [scenario scenarios]
      (when (contains? (:expect scenario) :exception)
        (let [e (get-in scenario [:expect :exception])]
          (when-not (and (map? e) (#{:load :evaluate} (:stage e))
                         (symbol? (:type e)) (nil? (namespace (:type e)))
                         (str/includes? (str (:type e)) ".")
                         (string? (:message-pattern e)) (seq (:message-pattern e))
                         (every? #{:stage :type :message-pattern :data} (keys e))
                         (or (not (contains? e :data)) (map? (:data e)))
                         (not (contains? (:expect scenario) :value)))
            (throw (ex-info "Expected exceptions require :stage, qualified :type, and :message-pattern"
                            {:scenario scenario})))
          (re-pattern (:message-pattern e))))
      (when-not (every? #(and (string? %) (.isFile (io/file %))) (:load-files scenario))
        (throw (ex-info "Scenario :load-files must name existing source files" {:scenario scenario}))))
    (assoc options :scenarios scenarios)))

(defn- stop-process! [^Process process]
  (with-open [children (.descendants (.toHandle process))]
    (doseq [^ProcessHandle child (reverse (.toList children))] (.destroyForcibly child)))
  (.destroyForcibly process)
  (.waitFor process))

(defn- audit [directory job result]
  (try
    (let [read-lines (fn [name]
                       (let [f (io/file directory name)]
                         (if (.isFile f) (mapv edn/read-string (str/split-lines (slurp f))) [])))
          attempts (read-lines "compiler-attempts.ednlog")
          events (read-lines "native-events.ednlog")
          evaluation (filterv #(= :evaluate (:stage %)) attempts)
          bad-prepared? (and (= :prepared (:compilation job))
                             (or (seq (if (:strict-load? job) attempts evaluation))
                                 (some #(and (or (:strict-load? job) (= :evaluate (:stage %)))
                                             (#{:compiled :compile-failed :disk-cache-hit} (:event %))) events)))]
      (cond-> (assoc result :final-compiler-attempts attempts :final-native-events events
                     :final-all-native-events (read-lines "all-native-events.ednlog"))
        bad-prepared? (assoc :status :failed :accepted? false :reason :evaluation-native-work)))
    (catch Exception e
      (assoc result :status :failed :accepted? false :reason :invalid-final-audit :message (ex-message e)))))

(defn- worker! [root options job]
  (let [directory (io/file root (:job-id job))
        _ (.mkdirs directory)
        request (io/file directory "request.edn")
        receipt (io/file directory "result.edn")
        job (assoc job :result-file (.getAbsolutePath receipt))
        _ (spit request (pr-str job))
        command [(str (System/getProperty "java.home") "/bin/java")
                 (str "-Xmx" (:heap-mib options) "m") "-XX:ActiveProcessorCount=2"
                 "--enable-native-access=ALL-UNNAMED"
                 (str "-Daguafria.cache-dir=" (:cache-dir job))
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "aguafria.zig.check-worker" (.getAbsolutePath request)]
        started (System/nanoTime)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectOutput (io/file directory "stdout.log"))
                          (.redirectError (io/file directory "stderr.log"))))
        completed (try (.waitFor process (long (:timeout-seconds options)) TimeUnit/SECONDS)
                       (catch InterruptedException e
                         (stop-process! process)
                         (.interrupt (Thread/currentThread))
                         (throw e)))
        _ (when-not completed (stop-process! process))
        result (if (.isFile receipt)
                 (try (edn/read-string (slurp receipt))
                      (catch Exception e {:status :failed :reason :invalid-worker-receipt :message (ex-message e)}))
                 {:status :failed :reason :missing-worker-receipt})
        result (audit directory job result)
        result (cond-> (assoc result :worker {:exit (.exitValue process)
                                              :elapsed-ms (/ (- (System/nanoTime) started) 1e6)
                                              :directory (.getAbsolutePath directory)})
                 (or (not completed) (not (zero? (.exitValue process))))
                 (assoc :status :failed :accepted? false
                        :reason (if completed :worker-exit :timeout)))]
    (spit (io/file directory "supervision.edn") (pr-str (:worker result)))
    result))

(defn check!
  "Check explicit project workloads without changing the live native cache.

  :tests [qualified-a-deftest ...] expects passed test results.
  :scenarios [{:id id :call qualified-symbol :args []
               :expect {:value edn-value | :status :passed | :error-name :Name,
                        :stdout exact-text, :stderr exact-text}}].
  Expectations can combine keys. Non-test entries need explicit expectations.

  Execution uses the normal native path.
  :compilation-modes defaults to [:on-demand :prepared].
  :precompile-options augments the producer's public precompile! options.
  :compiler-configuration is applied through configure! before project loading in
  producer and consumer workers (for example external object linkage). It cannot
  override the isolated cache directory or the embedded compiler.
  :report-dir must not exist; otherwise creates .aguafria/checks/<unique-run>.
  :prepared-cache-dir reuses an existing producer cache without running another
  producer; requires :compilation-modes [:prepared]. :strict-load? also forbids
  compiler work and disk fallback during bootstrap/namespace loading.
  Scenario :load-files are loaded before evaluation, as with editor buffer loads.
  :expect may include :exception {:stage :load|:evaluate :type qualified-symbol
  :message-pattern regex-string :data optional-subset-map}. This matches the root
  exception and records its actual diagnostic. Compiler-policy exceptions never
  qualify; strict compiler/fallback audits still apply to expected exceptions.
  :parallelism bounds concurrent isolated consumers (default 1).
  Each on-demand consumer has a fresh cache. Prepared consumers share only their
  producer's new cache, not a JVM or application state. Compiler attempts and
  forbidden fallback events during evaluation make a prepared consumer fail,
  even when caught by application code. Load/bootstrap work is separate.

  Cross-mode comparison covers status and the fields requested in :expect.
  All stdout/stderr is retained; native test-runner diagnostics are NOT silently
  stripped to force agreement. :tests alone checks test outcomes, not output equality.

  Returns a durable report with :accepted?; false is NOT thrown away. Tests
  execute application code: use explicit workloads, not arbitrary namespace scans.
  Workloads must await their asynchronous work; detached/daemon activity is not
  complete workload coverage. Final process journals still reject late compiler
  attempts/fallbacks. Worker timeouts/heap settings are supervision, not a sandbox
  or memory cap. Trusted project code can itself perform arbitrary filesystem IO."
  [options]
  (let [options (validate-options options)
        root (.getAbsoluteFile
              (io/file (or (:report-dir options)
                           (str ".aguafria/checks/" (System/currentTimeMillis) "-" (UUID/randomUUID)))))
        _ (when (.exists root) (throw (ex-info "Checker run directory already exists" {:directory (str root)})))
        _ (Files/createDirectories (.toPath (.getParentFile root)) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/createDirectory (.toPath root) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (spit (io/file root "configuration.edn") (pr-str options))
        scenarios (:scenarios options)
        namespaces (vec (distinct (map (comp symbol namespace :call) scenarios)))
        cache (.getAbsolutePath (io/file (or (:prepared-cache-dir options) (io/file root "prepared-cache"))))
        preparation (when (some #{:prepared} (:compilation-modes options))
                      (if (:prepared-cache-dir options)
                        {:status :provided-cache :accepted? true :cache-dir cache
                         :note "No producer was run; consumer checks establish coverage, not cache provenance."}
                        (worker! root options {:job-id "prepare" :phase :prepare :cache-dir cache
                                               :namespaces namespaces :scenarios scenarios
                                               :compiler-configuration (:compiler-configuration options)
                                               :precompile-options (:precompile-options options)})))
        jobs (for [compilation (:compilation-modes options)
                   [i scenario] (map-indexed vector scenarios)] [compilation i scenario])
        results (with-open [executor (Executors/newFixedThreadPool (:parallelism options))]
                  (let [tasks (mapv
                               (fn [[compilation i scenario]]
                                 (.submit executor
                                          ^Callable
                                          (bound-fn []
                                            (let [id (str (name compilation) "-" i)
                                                  result (if (and (= :prepared compilation) (not (:accepted? preparation)))
                                                           {:status :blocked :accepted? false :reason :preparation-failed}
                                                           (worker! root options
                                                                    {:job-id id :phase :consume :compilation compilation
                                                                     :strict-load? (:strict-load? options)
                                                                     :compiler-configuration (:compiler-configuration options)
                                                                     :scenario scenario
                                                                     :cache-dir (if (= :prepared compilation) cache
                                                                                    (str (io/file root id "cache")))}))
                                                  result (assoc result :scenario (:id scenario) :compilation compilation)]
                                              (spit (io/file root (str id ".edn")) (pr-str result))
                                              (binding [*out* *err*]
                                                (println "Checked" (:id scenario) compilation (:status result)) (flush))
                                              result)))) jobs)]
                    (mapv #(.get ^java.util.concurrent.Future %) tasks)))
        comparisons (mapv (fn [[id rs]]
                            (let [fields (into #{:status}
                                               (keys (:expect (first (filter #(= id (:id %)) scenarios)))))]
                              {:scenario id :compared-fields fields
                               :consistent? (and (every? :accepted? rs)
                                                 (apply = (map #(select-keys (:observation %) fields) rs)))}))
                          (sort-by (comp str key) (group-by :scenario results)))
        report {:schema :aguafria-check-v2 :directory (str root) :preparation preparation
                :results results :comparisons comparisons
                :accepted? (and (every? :accepted? results) (every? :consistent? comparisons))
                :coverage-note "Only selected workloads and requested observations are checked; this is not whole-project coverage."}]
    (spit (io/file root "report.edn") (pr-str report))
    report))
