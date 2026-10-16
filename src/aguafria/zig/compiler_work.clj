(ns aguafria.zig.compiler-work
  "Bounded accounting of compiler commands for one preparation."
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong LongAdder]
           [java.util.function Function]))

(def ^:dynamic *collector* nil)
(def ^:dynamic *phase* :source-build)
(def ^:dynamic *owner* nil)
(def ^:dynamic *compiler-command-policy*
  "Optional run-scoped hook invoked before a compiler command starts. Throwing
  blocks the command; it still counts as an attempt when a collector is bound."
  nil)

(def ^:private compiler-actions
  #{"build-lib" "build-exe" "build-obj" "test" "test-obj" "run" "ast-check"})
(def ^:private sample-limit 32)
(def ^:private counter-factory (reify Function (apply [_ _] (LongAdder.))))

(defn collector []
  {:sequence (AtomicLong.)
   :counts (ConcurrentHashMap.)
   :command-times (ConcurrentHashMap.)
   :owner-counts (ConcurrentHashMap.)
   :samples (object-array sample-limit)})

(defn- command-kind [command]
  (cond
    (some #{"--show-builtin"} command) :metadata
    (compiler-actions (second command)) :compiler
    (#{"version" "env"} (second command)) :metadata
    (= "--flat" (second command)) :debug-information))

(defn- count! [^ConcurrentHashMap counts key]
  (.increment
   ^LongAdder
   (.computeIfAbsent counts key counter-factory)))

(defn run-command!
  "Count compiler attempts, including rejected builds, without retaining source
  text or diagnostics. When no preparation is active, call through directly."
  [command invoke]
  (let [invoke (fn []
                 (when (and *compiler-command-policy* (= :compiler (command-kind command)))
                   (*compiler-command-policy* command))
                 (invoke))]
    (if-let [kind (when *collector* (command-kind command))]
      (let [{:keys [sequence counts command-times owner-counts samples]} *collector*
            ordinal (.getAndIncrement ^AtomicLong sequence)
            phase *phase*
            action (second command)
            started (System/nanoTime)
            timing-key [*owner* kind phase action]]
        (count! counts [kind phase action])
        (count! owner-counts timing-key)
        (try
          (let [result (invoke)]
            (when (< ordinal sample-limit)
              (aset ^objects samples (int ordinal)
                    {:kind kind :phase phase :action action :exit (:exit result)}))
            result)
          (catch Throwable error
            (when (< ordinal sample-limit)
              (aset ^objects samples (int ordinal)
                    {:kind kind :phase phase :action action :spawn-error (str (class error))}))
            (throw error))
          (finally
            (.add ^LongAdder (.computeIfAbsent ^ConcurrentHashMap command-times timing-key counter-factory)
                  (- (System/nanoTime) started)))))
      (invoke))))

(defn report
  "Count the whole preparation, not just the pack builder. Warm hits count zero
  only when no compiler command was attempted. Debug/metadata stay separate."
  [{:keys [sequence counts command-times owner-counts samples]}]
  (let [timings (mapv (fn [[[owner kind phase action] duration]]
                        {:owner owner :kind kind :phase phase :action action
                         :invocations (.sum ^LongAdder (.get ^ConcurrentHashMap owner-counts [owner kind phase action]))
                         :duration-ms (/ (.sum ^LongAdder duration) 1e6)}) command-times)
        counts (into {} (map (fn [[key value]] [key (.sum ^LongAdder value)])) counts)
        sum-kind (fn [kind] (reduce + 0 (for [[[k _ _] n] counts :when (= k kind)] n)))
        compilations (sum-kind :compiler)]
    {:scope :aguafria-compiler-commands
     :compiler-invocations compilations
     :one-compilation? (<= compilations 1)
     :debug-information-commands (sum-kind :debug-information)
     :metadata-commands (sum-kind :metadata)
     ;; Command wall times overlap across workers; do not interpret their sum
     ;; as elapsed preparation time. Owner attribution includes load/build jobs.
     :timing-scope :summed-command-wall-time
     :command-timings (vec (sort-by (juxt (comp str :owner) :kind :phase :action) timings))
     :by-phase (reduce (fn [result [[kind phase action] count]]
                         (assoc-in result [phase kind action] count)) {} counts)
     :command-samples (vec (remove nil? samples))
     :omitted-command-samples (max 0 (- (.get ^AtomicLong sequence) sample-limit))}))
