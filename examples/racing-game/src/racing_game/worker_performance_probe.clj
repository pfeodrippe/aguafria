(ns racing-game.worker-performance-probe
  "JVM-hosted proof that the native workers publish real learned decisions."
  (:require [aguafria.zig :as az]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.worker :as worker])
  (:import [java.lang.foreign Arena]))

(defn await-result
  [racer revision timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [result (az/value (worker/result-for racer (dec revision)))]
        (cond
          (= revision (:revision result)) result
          (< (System/nanoTime) deadline) (do (Thread/sleep 5) (recur))
          :else
          (throw
           (ex-info "Native inference worker timed out"
                    {:racer racer
                     :revision revision
                     :worker (az/value (worker/summary))})))))))

(defn request
  [racer revision]
  (worker/InferenceRequest
   {:valid true
    :racer racer
    :rank (inc racer)
    :lap 0
    :item (mod racer 5)
    :target (mod (inc racer) 8)
    :persona (mod racer 3)
    :target_distance (min 9 (inc racer))
    :target_lane (mod racer 3)
    :tactical_status (mod racer 4)
    :urgent (zero? racer)
    :observation_schema protocol/observation-schema-version
    :action_schema protocol/action-schema-version
    :revision revision
    :race_epoch 1
    :simulation_tick revision
    :progress (* racer 0.05)
    :speed 0.07
    :enqueue_seconds 0.0}))

(defn run-batch!
  [revision racer-count check-in-flight?]
  (let [started (System/nanoTime)]
    (doseq [racer (range racer-count)]
      (assert (worker/submit! (request racer revision))))
    (when check-in-flight?
      (assert (false? (worker/submit! (request 0 (inc revision))))))
    (let [results (mapv #(await-result % revision 30000)
                        (range racer-count))
          wall-ms (/ (- (System/nanoTime) started) 1000000.0)]
      {:results
       (mapv #(select-keys %
                           [:racer :valid :accepted :best_token
                            :response_bytes :input_token_count :queue_us
                            :prefill_us :total_us :tokens_per_second :sampler_state
                            :observation_schema :action_schema])
             results)
       :wall-ms wall-ms
       :decisions-per-second (/ (* racer-count 1000.0) wall-ms)
       :worker (az/value (worker/summary))})))

(defn percentile
  "Nearest-rank percentile over a non-empty numeric collection."
  [values probability]
  (let [ordered (vec (sort values))
        index (-> (* probability (count ordered))
                  Math/ceil
                  long
                  dec
                  (max 0)
                  (min (dec (count ordered))))]
    (nth ordered index)))

(defn distribution
  [values]
  {:minimum (first (sort values))
   :p50 (percentile values 0.50)
   :p95 (percentile values 0.95)
   :p99 (percentile values 0.99)
   :maximum (last (sort values))})

(defn run-sustained!
  "Measure fresh consecutive eight-racer decisions. The warm-up batch is
  reported separately and excluded from the latency distribution."
  [batch-count]
  (let [warm-up (run-batch! 1 8 true)
        started (System/nanoTime)
        batches (mapv #(run-batch! (+ 2 %) 8 false) (range batch-count))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
        results (mapcat :results batches)
        total-ms (map #(/ (double (:total_us %)) 1000.0) results)
        queue-ms (map #(/ (double (:queue_us %)) 1000.0) results)
        batch-ms (map :wall-ms batches)
        by-racer (->> results
                      (group-by :racer)
                      (into (sorted-map)
                            (map (fn [[racer entries]]
                                   [racer (count entries)]))))]
    {:warm-up-ms (:wall-ms warm-up)
     :batches batch-count
     :decisions (count results)
     :elapsed-ms elapsed-ms
     :decisions-per-second (/ (* (count results) 1000.0) elapsed-ms)
     :accepted (count (filter :accepted results))
     :valid (count (filter :valid results))
     :decisions-by-racer by-racer
     :decision-latency-ms (distribution total-ms)
     :queue-latency-ms (distribution queue-ms)
     :batch-latency-ms (distribution batch-ms)
     :worker (az/value (worker/summary))}))

(defn -main
  [& [mode batch-count-text]]
  (with-open [arena (Arena/ofConfined)]
    (try
      (model/verify-assets!)
      (let [loaded
            (az/value
             (inference/load-model!
              (.allocateFrom arena (str (model/model-file)))))]
        (assert (:valid loaded))
        (println :loaded (select-keys loaded [:valid :tensor_count])))
      (let [loaded
            (az/value
             (inference/load-action-head!
              (.allocateFrom arena (str (model/action-head-file)))))]
        (assert (:valid loaded))
        (println :action-head
                 (select-keys loaded
                              [:valid :input_count :output_count
                               :observation_schema :action_schema])))
      (assert (worker/start!))
      (println :started (az/value (worker/summary)))
      (if (= mode "sustained")
        (let [batch-count (if batch-count-text
                            (Long/parseLong batch-count-text)
                            30)]
          (when-not (pos? batch-count)
            (throw (ex-info "Sustained batch count must be positive"
                            {:batch-count batch-count})))
          (println :sustained (run-sustained! batch-count)))
        (let [racer-count (if (= mode "all") 8 1)]
          (println :first-batch (run-batch! 1 racer-count (= racer-count 8)))
          (when (= racer-count 8)
            (println :warm-batch (run-batch! 2 racer-count false)))))
      (finally
        (worker/stop!)
        (inference/unload-model!))))
  (shutdown-agents))
