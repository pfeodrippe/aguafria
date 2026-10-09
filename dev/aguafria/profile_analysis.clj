(ns aguafria.profile-analysis
  "Bounded JFR summaries for the precompilation profiler."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [jdk.jfr.consumer RecordingFile]))

(defn- stack [event]
  (when-let [trace (.getStackTrace event)]
    (mapv (fn [frame]
            (let [method (.getMethod frame)]
              (str (.getName (.getType method)) "/" (.getName method))))
          (.getFrames trace))))

(defn- application-frame [frames]
  (or (first (filter #(str/starts-with? % "aguafria.") frames))
      (first frames) "no-stack"))

(defn- increment [summary path amount]
  (update-in summary path (fnil + 0) amount))

(defn- measure [summary event]
  (let [name (.getName (.getEventType event))
        frames (when (contains? #{"jdk.ExecutionSample" "jdk.ObjectAllocationSample"
                                  "jdk.JavaMonitorEnter" "jdk.ThreadPark"} name)
                 (stack event))
        frame (application-frame frames)]
    (case name
      "jdk.ExecutionSample"
      (-> summary
          (increment [:cpu-samples frame] 1)
          (increment [:cpu-stacks (str/join "\n" (take 12 frames))] 1))

      "jdk.ObjectAllocationSample"
      (-> summary
          (increment [:allocation-weight-by-frame frame] (.getLong event "weight"))
          (increment [:allocation-weight-by-class (.getName (.getClass event "objectClass"))]
                     (.getLong event "weight")))

      "jdk.JavaMonitorEnter"
      (-> summary
          (increment [:monitor-ms frame] (/ (.toNanos (.getDuration event)) 1e6))
          (increment [:monitor-counts frame] 1))

      "jdk.ThreadPark"
      (increment summary [:park-ms frame] (/ (.toNanos (.getDuration event)) 1e6))

      "jdk.GarbageCollection"
      (-> summary
          (increment [:gc :count] 1)
          (increment [:gc :duration-ms] (/ (.toNanos (.getDuration event)) 1e6)))

      "jdk.CPULoad"
      (-> summary
          (increment [:cpu-load :samples] 1)
          (increment [:cpu-load :jvm-user-sum] (.getFloat event "jvmUser"))
          (increment [:cpu-load :jvm-system-sum] (.getFloat event "jvmSystem")))

      summary)))

(defn- top [entries]
  (vec (take 30 (sort-by val > entries))))

(defn summarize-jfr! [directory]
  (let [summary
        (with-open [recording (RecordingFile. (.toPath (io/file directory "profile.jfr")))]
          (loop [summary {}]
            (if (.hasMoreEvents recording)
              (recur (measure summary (.readEvent recording)))
              summary)))
        summary (assoc summary
                       :cpu-samples-total (reduce + 0 (vals (:cpu-samples summary)))
                       :allocation-weight-total
                       (reduce + 0 (vals (:allocation-weight-by-frame summary)))
                       :monitor-total-ms (reduce + 0.0 (vals (:monitor-ms summary)))
                       :park-total-ms (reduce + 0.0 (vals (:park-ms summary))))
        result (reduce (fn [result key] (update result key top)) summary
                       [:cpu-samples :cpu-stacks :allocation-weight-by-frame
                        :allocation-weight-by-class :monitor-ms :monitor-counts :park-ms])]
    (spit (io/file directory "jfr-summary.edn") (pr-str result))
    result))

(defn interval-union-ms [intervals]
  (let [sorted (sort-by first intervals)]
    (loop [remaining (rest sorted) current (first sorted) total 0.0]
      (if-let [[start end :as next] (first remaining)]
        (if (<= start (second current))
          (recur (rest remaining) [(first current) (max end (second current))] total)
          (recur (rest remaining) next (+ total (- (second current) (first current)))))
        (+ total (if current (- (second current) (first current)) 0.0))))))

(defn summarize-intervals! [directory]
  (let [events (with-open [reader (io/reader (io/file directory "events.edn"))]
                 (mapv edn/read-string (line-seq reader)))
        commands (filter #(= :command (:kind %)) events)
        spans (filter #(= :span (:kind %)) events)
        children (group-by :parent events)
        exclusive
        (group-by :stage
                  (for [span spans
                        :let [direct-children
                              (filter #(and (:end-ms %) (= (:thread %) (:thread span)))
                                      (get children (:id span)))]]
                    (assoc span :exclusive-ms
                           (- (- (:end-ms span) (:start-ms span))
                              (interval-union-ms (map (juxt :start-ms :end-ms) direct-children))))))
        result {:command-wall-union-ms
                (interval-union-ms (map (juxt :start-ms :end-ms) commands))
                :exclusive-stage-ms-sum
                (into (sorted-map)
                      (for [[stage xs] exclusive]
                        [(str stage) (reduce + 0.0 (map :exclusive-ms xs))]))}]
    (spit (io/file directory "intervals.edn") (pr-str result))
    result))

(defn -main [directory]
  (prn {:jfr (summarize-jfr! directory)
        :intervals (summarize-intervals! directory)}))
