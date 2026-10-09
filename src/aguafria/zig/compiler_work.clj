(ns aguafria.zig.compiler-work
  "Bounded accounting of compiler commands for one preparation."
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong LongAdder]
           [java.util.function Function]))

(def ^:dynamic *collector* nil)
(def ^:dynamic *phase* :source-build)

(def ^:private compiler-actions
  #{"build-lib" "build-exe" "build-obj" "test" "test-obj" "run" "ast-check"})
(def ^:private sample-limit 32)
(def ^:private counter-factory (reify Function (apply [_ _] (LongAdder.))))

(defn collector []
  {:sequence (AtomicLong.)
   :counts (ConcurrentHashMap.)
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
  (if-let [kind (when *collector* (command-kind command))]
    (let [{:keys [sequence counts samples]} *collector*
          ordinal (.getAndIncrement ^AtomicLong sequence)
          phase *phase*
          action (second command)]
      (count! counts [kind phase action])
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
          (throw error))))
    (invoke)))

(defn report
  "Count the whole preparation, not just the pack builder. Warm hits count zero
  only when no compiler command was attempted. Debug/metadata stay separate."
  [{:keys [sequence counts samples]}]
  (let [counts (into {} (map (fn [[key value]] [key (.sum ^LongAdder value)])) counts)
        sum-kind (fn [kind] (reduce + 0 (for [[[k _ _] n] counts :when (= k kind)] n)))
        compilations (sum-kind :compiler)]
    {:scope :aguafria-compiler-commands
     :compiler-invocations compilations
     :one-compilation? (<= compilations 1)
     :debug-information-commands (sum-kind :debug-information)
     :metadata-commands (sum-kind :metadata)
     :by-phase (reduce (fn [result [[kind phase action] count]]
                         (assoc-in result [phase kind action] count)) {} counts)
     :command-samples (vec (remove nil? samples))
     :omitted-command-samples (max 0 (- (.get ^AtomicLong sequence) sample-limit))}))
