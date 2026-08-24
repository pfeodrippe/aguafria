(ns racing-game.worker-performance-probe
  "JVM-hosted proof that the native worker publishes one real LLM decision."
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
                            :prefill_us :tokens_per_second :sampler_state
                            :observation_schema :action_schema])
             results)
       :wall-ms wall-ms
       :decisions-per-second (/ (* racer-count 1000.0) wall-ms)
       :worker (az/value (worker/summary))})))

(defn -main
  [& [mode]]
  (with-open [arena (Arena/ofConfined)]
    (try
      (let [loaded
            (az/value
             (inference/load-model!
              (.allocateFrom arena (str (model/model-file)))))]
        (assert (:valid loaded))
        (println :loaded (select-keys loaded [:valid :tensor_count])))
      (assert (worker/start!))
      (println :started (az/value (worker/summary)))
      (let [racer-count (if (= mode "all") 8 1)]
        (println :first-batch (run-batch! 1 racer-count (= racer-count 8)))
        (when (= racer-count 8)
          (println :warm-batch (run-batch! 2 racer-count false))))
      (finally
        (worker/stop!)
        (inference/unload-model!))))
  (shutdown-agents))
