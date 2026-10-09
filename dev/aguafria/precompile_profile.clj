(ns aguafria.precompile-profile
  "Isolated developer profiling. No production cache or compiler policy changes."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.zig.explain :as explain]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.time Duration]
           [java.util.concurrent ConcurrentLinkedQueue]
           [java.util.concurrent.atomic AtomicLong]
           [jdk.jfr Configuration Recording]))

(def sample-namespaces
  '[learn.example.values
    learn.example.test-arrays
    learn.example.test-multidimensional-arrays
    learn.example.test-vector
    learn.example.test-single-item-pointer
    learn.example.test-pointer-arithmetic
    learn.example.test-pointer-casting
    learn.example.test-optional-type
    learn.example.test-error-union
    learn.example.test-structs])

(def ^:dynamic *context* [])
(def max-events 200000)

(def stages
  '[aguafria.zig.precompile/load-namespace!
    aguafria.zig.precompile/prepare-scalar-profiles!
    aguafria.zig.runtime/precompile-namespace-load!
    aguafria.zig.runtime/check-test-definition!
    aguafria.zig.runtime/precompile-test!
    aguafria.zig.runtime/build-native-test-plan!
    aguafria.zig.runtime/build-frozen-compilation-slice!
    aguafria.zig.runtime/inspect-module!
    aguafria.zig.runtime/compilation-plan
    aguafria.zig.runtime/compile-plan!
    aguafria.zig.discovery/analyze!
    aguafria.zig.discovery/prepare!
    aguafria.zig.discovery/prepare-declared-functions!
    aguafria.zig.discovery/prepare-constant-readers!
    aguafria.zig.discovery/refine-jvm-map-representations!
    aguafria.zig.discovery/refine-jvm-type-representations!
    aguafria.zig.discovery/refine-jvm-value-representations!
    aguafria.zig.discovery/inspect-jvm-construction-inputs!
    aguafria.zig.bundle/finish!
    aguafria.zig.bundle/build-pack!
    aguafria.zig.bundle/materialize-entry!])

(defn- recorder [directory]
  (.mkdirs (io/file directory "commands"))
  {:directory directory
   :origin (System/nanoTime)
   :ids (AtomicLong.)
   :count (AtomicLong.)
   :dropped (AtomicLong.)
   :events (ConcurrentLinkedQueue.)})

(defn- record! [{:keys [count dropped events]} event]
  (if (<= (.incrementAndGet ^AtomicLong count) max-events)
    (.add ^ConcurrentLinkedQueue events event)
    (.incrementAndGet ^AtomicLong dropped)))

(defn- elapsed-ms [origin]
  (/ (- (System/nanoTime) origin) 1e6))

(defn- span [state stage f args]
  (let [id (.incrementAndGet ^AtomicLong (:ids state))
        started (elapsed-ms (:origin state))
        thread (.threadId (Thread/currentThread))
        status (volatile! :ok)]
    (binding [*context* (conj *context* [id stage])]
      (try
        (apply f args)
        (catch Throwable error
          (vreset! status :threw)
          (throw error))
        (finally
          (record! state {:kind :span :id id :stage stage
                          :parent (first (peek (pop *context*)))
                          :thread thread :status @status
                          :start-ms started :end-ms (elapsed-ms (:origin state))}))))))

(defn parse-time
  "Parse macOS time -l output; resource data is separate from compiler stderr."
  [output]
  (let [match-number (fn [pattern]
                       (some-> (re-find pattern output) second Double/parseDouble))]
    {:real-seconds (match-number #"(?m)^real\s+([\d.]+)$")
     :user-seconds (match-number #"(?m)^user\s+([\d.]+)$")
     :sys-seconds (match-number #"(?m)^sys\s+([\d.]+)$")
     :max-rss-bytes (match-number #"(?m)^\s*(\d+)\s+maximum resident set size$")}))

(defn- measured-sh [state original args]
  (let [[command options] (split-with (complement keyword?) args)
        id (.incrementAndGet ^AtomicLong (:ids state))
        timing (io/file (:directory state) "commands" (str id ".time"))
        started (elapsed-ms (:origin state))
        result (apply original
                      (concat ["/usr/bin/time" "-l" "-p" "-o" (str timing)]
                              command options))]
    (record! state (merge {:kind :command :id id :command (vec command)
                           :parent (first (peek *context*)) :context (mapv second *context*)
                           :thread (.threadId (Thread/currentThread))
                           :start-ms started :end-ms (elapsed-ms (:origin state))
                           :exit (:exit result)}
                          (parse-time (slurp timing))))
    result))

(defn verify-shell!
  "Positive/negative exit, stdout/stderr preservation and timing overhead check."
  [directory]
  (let [state (recorder directory)
        direct shell/sh
        measured #(measured-sh state direct %)
        commands [["/bin/sh" "-c" "printf stdout; printf stderr >&2; exit 0"]
                  ["/bin/sh" "-c" "printf stdout; printf stderr >&2; exit 7"]]
        _ (doseq [command commands]
            (assert (= (apply direct command)
                       (with-redefs [shell/sh (fn [& args] (measured args))]
                         (apply shell/sh command)))))
        timed (fn [f]
                (let [started (System/nanoTime)]
                  (dotimes [_ 20] (f))
                  (elapsed-ms started)))
        baseline (timed #(direct "/usr/bin/true"))
        instrumented (timed #(measured ["/usr/bin/true"]))]
    {:verified-commands (count commands) :iterations 20
     :direct-ms baseline :instrumented-ms instrumented
     :added-ms-per-command (/ (- instrumented baseline) 20)
     :measured-events (.size ^ConcurrentLinkedQueue (:events state))}))

(defn- wrappers [state]
  (let [original-sh shell/sh]
    (into {#'shell/sh (fn [& args] (measured-sh state original-sh args))}
          (for [stage-symbol stages
                :let [v (or (ns-resolve (symbol (namespace stage-symbol))
                                        (symbol (name stage-symbol)))
                            (throw (ex-info "Missing profile stage" {:symbol stage-symbol})))
                      original @v]]
            [v (fn [& args] (span state stage-symbol original args))]))))

(defn- sum [key events]
  (reduce + 0.0 (keep key events)))

(defn summarize [events]
  (let [commands (filter #(= :command (:kind %)) events)
        spans (filter #(= :span (:kind %)) events)]
    {:commands {:count (count commands) :exits (frequencies (map :exit commands))
                :wall-ms-sum (reduce + 0.0 (map #(- (:end-ms %) (:start-ms %)) commands))
                :user-seconds (sum :user-seconds commands)
                :sys-seconds (sum :sys-seconds commands)
                :max-rss-bytes (reduce max 0.0 (keep :max-rss-bytes commands))}
     :command-contexts
     (into (sorted-map)
           (for [[stage xs] (group-by #(or (peek (:context %)) 'outside-stage) commands)]
             [(str stage) {:count (count xs)
                           :wall-ms-sum (reduce + 0.0 (map #(- (:end-ms %) (:start-ms %)) xs))
                           :cpu-seconds (+ (sum :user-seconds xs) (sum :sys-seconds xs))}]))
     :stages
     (into (sorted-map)
           (for [[stage xs] (group-by :stage spans)]
             [(str stage) {:count (count xs) :statuses (frequencies (map :status xs))
                           :inclusive-ms-sum
                           (reduce + 0.0 (map #(- (:end-ms %) (:start-ms %)) xs))}]))}))

(defn- start-jfr! []
  (let [recording (Recording. (Configuration/getConfiguration "profile"))]
    (.setName recording "Learn ten-namespace precompilation")
    (.setMaxSize recording (* 512 1024 1024))
    (.withThreshold (.enable recording "jdk.JavaMonitorEnter") (Duration/ofMillis 1))
    (.withThreshold (.enable recording "jdk.ThreadPark") (Duration/ofMillis 1))
    (.start recording)
    recording))

(defn run!
  "Profile the fixed sample in a fresh JVM. Supply an isolated existing run directory."
  [directory cache-dir]
  (when (some find-ns sample-namespaces)
    (throw (ex-info "Profile must start in a fresh JVM" {})))
  (let [state (recorder directory)
        original (runtime/configuration)
        recording (start-jfr!)
        report-file (str (io/file directory "precompile.edn"))
        blocked (fn [& _] (throw (ex-info "Preparation invoked native code" {})))
        definitions (assoc (wrappers state)
                           #'runtime/invoke! blocked #'runtime/invoke-with-result! blocked)
        started (System/nanoTime)]
    (runtime/configure! {:cache-dir cache-dir})
    (try
      (let [report (binding [explain/*reporter*
                             #(record! state (assoc (select-keys % [:event :module :duration-ms
                                                                    :artifact-key :bundle-id])
                                                    :kind :cache-event))]
                     (with-redefs-fn definitions
                       #(span state :precompile
                              precompile/precompile!
                              [{:analyze sample-namespaces :parallelism 4
                                :report-file report-file}])))
            result {:directory directory :cache-dir cache-dir
                    :namespaces sample-namespaces
                    :native-invocation-blocked? true
                    :duration-ms (elapsed-ms started)
                    :coverage (:coverage report) :bundles (:bundles report)
                    :compiler-work (:compiler-work report)}]
        (spit (io/file directory "result.edn") (pr-str result))
        (when (or (not= 10 (get-in result [:coverage :namespaces :attempted]))
                  (not= {:analyzed 10} (get-in result [:coverage :namespaces :statuses]))
                  (not (pos? (get-in result [:coverage :operations :fully-prepared]))))
          (throw (ex-info "Profile sample did not prepare successfully" {:result result})))
        (prn result)
        result)
      (finally
        (runtime/configure! original)
        (.stop recording)
        (.dump recording (.toPath (io/file directory "profile.jfr")))
        (.close recording)
        (let [events (vec (.toArray ^ConcurrentLinkedQueue (:events state)))
              summary (assoc (summarize events)
                             :dropped-events (.get ^AtomicLong (:dropped state)))]
          (with-open [writer (io/writer (io/file directory "events.edn"))]
            (doseq [event events] (.write writer (str (pr-str event) "\n"))))
          (spit (io/file directory "timings.edn") (pr-str summary))
          (prn {:profile-summary summary}))))))

(defn -main [directory cache-dir]
  (when-not (and directory cache-dir (.isDirectory (io/file directory)))
    (throw (ex-info "Supply an existing run directory and isolated native-cache path" {})))
  (try
    (run! directory cache-dir)
    (finally (shutdown-agents))))
