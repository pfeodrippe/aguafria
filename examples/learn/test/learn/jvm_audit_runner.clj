(ns learn.jvm-audit-runner
  "Parallel, checkpointed JVM audit. Each worker evaluates ordinary Clojure forms
  in-process; only the audit processes are isolated, not the public JVM API."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [learn.jvm-audit :as audit])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files StandardCopyOption]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def all-kinds
  #{:var :initializer :native-test :body-expression :local-initializer
    :local-value :subexpression :entry-call :comment-call})

(defn write-report! [path value]
  (io/make-parents path)
  (let [target (.toPath (io/file path))
        temporary (.toPath (io/file (str path ".writing")))]
    (spit (.toFile temporary) (pr-str value))
    (Files/move temporary target
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))))

(defn read-report [path]
  (when (.isFile (io/file path))
    (edn/read-string (slurp path))))

(defn- case-path [directory index]
  (str (io/file directory "cases" (format "%06d.edn" index))))

(defn- worker-job! [{:keys [example directory start-index]}]
  (let [progress (str (io/file directory "progress.edn"))
        on-case (fn [{:keys [phase index case] :as event}]
                  (when (= :evaluated phase)
                    (write-report! (case-path directory index) case))
                  (write-report! progress (assoc event :at (System/currentTimeMillis))))]
    (on-case {:phase :loading :start-index start-index})
    (try
      (let [result (audit/audit-example!
                    example {:kinds all-kinds :disposable? true
                             :start-index start-index :on-case on-case
                             :stop-after-panic? true})]
        (write-report! (str (io/file directory "load.edn")) (:load result))
        (on-case {:phase :done :load (:load result)}))
      (catch Throwable error
        (if (:audit/restart (ex-data error))
          (on-case {:phase :restart :index (:index (ex-data error))})
          (on-case {:phase :worker-error :error (audit/error-details error)}))))))

(defn -main [& _]
  ;; Sequential platform thread: native TLS and stdout capture are process state.
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (worker-job! (edn/read-string line))))

(defn- start-worker! [root slot]
  (let [directory (io/file root (str "worker-" slot))
        _ (.mkdirs directory)
        java (str (io/file (System/getProperty "java.home") "bin" "java"))
        command [java "-Xmx3g" "-XX:-OmitStackTraceInFastThrow"
                 "--enable-native-access=ALL-UNNAMED"
                 (str "-Daguafria.cache-dir=" (.getAbsolutePath (io/file directory "native")))
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "learn.jvm-audit-runner"]
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    (.redirectOutput (ProcessBuilder$Redirect/appendTo (io/file directory "worker.log")))
                    .start)]
    {:process process :input (io/writer (.getOutputStream process))}))

(defn- stop-worker! [{:keys [^Process process input]}]
  (when process
    ;; These are descendants of this exact owned audit worker, not PID/name scans.
    (with-open [children (.descendants (.toHandle process))]
      (doseq [^ProcessHandle child (iterator-seq (.iterator children))]
        (.destroyForcibly child)))
    (.destroyForcibly process)
    (.waitFor process 5 TimeUnit/SECONDS)
    (try (.close ^java.io.Writer input) (catch java.io.IOException _))))

(defn- submit! [{:keys [input]} job]
  (.write ^java.io.Writer input (str (pr-str job) "\n"))
  (.flush ^java.io.Writer input))

(defn- await-job [worker directory timeout-ms load-timeout-ms]
  (let [submitted (System/currentTimeMillis)]
    (loop []
      (let [progress (read-report (str (io/file directory "progress.edn")))
            phase (:phase progress)
            idle (- (System/currentTimeMillis) (or (:at progress) submitted))
            limit (if (or (nil? phase) (= :loading phase)) load-timeout-ms timeout-ms)]
        (cond
          (#{:done :restart :worker-error} phase) progress
          (not (.isAlive ^Process (:process worker)))
          (assoc progress :phase :worker-exited
                         :exit-code (.exitValue ^Process (:process worker)))
          (> idle limit) (assoc progress :phase :timeout :timeout-ms limit)
          :else (do (Thread/sleep 200) (recur)))))))

(defn- file-id [example]
  (str/replace (:source example) #"[^a-zA-Z0-9_-]" "_"))

(defn- audit-file! [worker root slot example options]
  (let [directory (.getAbsolutePath (io/file root "files" (file-id example)))
        source-text (slurp (io/resource (:source example)))
        inventory (audit/plan source-text)
        cases (:cases inventory)
        started (System/nanoTime)]
    (loop [start-index 0 restarts 0]
      (when-not @worker (reset! worker (start-worker! root slot)))
      (write-report! (str (io/file directory "progress.edn"))
                     {:phase :loading :at (System/currentTimeMillis)})
      (submit! @worker {:example example :directory directory :start-index start-index})
      (let [{:keys [phase index] :as event}
            (await-job @worker directory (:timeout-ms options) (:load-timeout-ms options))
            done? (= :done phase)
            next-index (if (some? index) (inc index) start-index)]
        (when-not done?
          (stop-worker! @worker)
          (reset! worker nil)
          (when (and (some? index) (not= :restart phase)
                     (nil? (read-report (case-path directory index))))
            (write-report! (case-path directory index)
                           (assoc (nth cases index) :status phase
                                  :worker-event (dissoc event :case)))))
        (if (and (not done?) (< next-index (count cases))
                 (or (some? index) (and (= :worker-exited phase) (zero? restarts)))
                 (< restarts (:max-restarts options)))
          (recur next-index (inc restarts))
          (let [results (mapv (fn [index case]
                                (or (read-report (case-path directory index))
                                    (assoc case :status :blocked-by-worker
                                                :worker-event (dissoc event :case))))
                              (range) cases)
                result (assoc example :namespace (:namespace inventory)
                                      :load (or (:load event)
                                                (read-report (str (io/file directory "load.edn"))))
                                      :upstream-expectation (audit/upstream-expectation (:zig example))
                                      :state-policy :ordered-shared-file-state
                                      :source-changed? (not= source-text (slurp (io/resource (:source example))))
                                      :restarts restarts :completion phase :cases results
                                      :elapsed-ms (/ (- (System/nanoTime) started) 1e6))]
            (write-report! (str (io/file directory "result.edn")) result)
            ;; Explicitly dangerous examples never leave native state in a reused worker.
            (when (= :requires-disposable-jvm (audit/execution-policy (:zig example)))
              (stop-worker! @worker)
              (reset! worker nil))
            result))))))

(defn run-parallel!
  "Audit every authored Learn example with bounded independent JVM workers.
  Each file is checkpointed, including interrupted cases and unexecuted contexts."
  [{:keys [jobs root files] :as options
    :or {jobs 4}}]
  (let [root (or root (str "build/jvm-audit-full/" (UUID/randomUUID)))
        _ (when (.exists (io/file root))
            (throw (ex-info "Use a new audit directory; old checkpoints must not be reused"
                            {:root root})))
        options (merge {:timeout-ms 60000 :load-timeout-ms 180000 :max-restarts 12} options)
        examples (cond->> (audit/corpus) files (filter #(contains? (set files) (:zig %))))
        pending (atom (vec examples))
        completed (atom [])
        started (System/nanoTime)]
    (write-report! (str (io/file root "manifest.edn"))
                   {:examples (vec examples) :options options :jobs jobs
                    :started-at (str (java.time.Instant/now))})
    (audit/parallel-inventory
     jobs
     (fn [slot]
       (let [worker (atom nil)]
         (try
           (loop []
             (when-let [example (first (first (swap-vals! pending #(vec (rest %)))))]
               (let [result (try (audit-file! worker root slot example options)
                                 (catch Throwable e
                                   (stop-worker! @worker)
                                   (reset! worker nil)
                                   (assoc example :completion :inventory-error
                                                  :error (audit/error-details e) :cases [])))]
                 (swap! completed conj result)
                 (locking completed
                   (println (count @completed) "/" (count examples) (:zig example)
                            (frequencies (map :status (:cases result))))
                   (flush)))
               (recur)))
           (finally (stop-worker! @worker)))))
     (range jobs))
    (let [results (vec (sort-by :zig @completed))
          report {:examples results :jobs jobs :native-execution :parallel-isolated-jvms
                  :summary (frequencies (map :status (mapcat :cases results)))
                  :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
      (write-report! (str (io/file root "report.edn")) report)
      (assoc (dissoc report :examples) :report (str (io/file root "report.edn"))))))
