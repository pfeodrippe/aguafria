(ns racing-game.performance
  "Explicit JVM-side measurement helpers for the native racing loop.

  These functions pace and observe the same Aguafria/Flecs functions used by
  the desktop. They are development tools and are absent from the standalone
  dependency graph."
  (:require [aguafria.zig :as az]
            [racing-game.core :as game]
            [racing-game.simulation :as simulation]))

(def ^:private frame-nanos 8333333)

(defn percentile
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
  (let [values (vec values)]
    {:minimum (apply min values)
     :p50 (percentile values 0.50)
     :p95 (percentile values 0.95)
     :p99 (percentile values 0.99)
     :maximum (apply max values)
     :mean (/ (reduce + 0.0 values) (count values))}))

(defn run-live-window!
  "Pace the native simulation at 120 Hz while all eight LLM workers run.

  Returns native-step latency, scheduling/deadline counters, and per-racer
  worker fairness. `:reset?` defaults to true. This does not render or create a
  window, so it is suitable for repeatable nREPL measurements."
  ([]
   (run-live-window! {}))
  ([{:keys [ticks seed reset?]
     :or {ticks 1200 seed 0 reset? true}}]
   (when-not (and (integer? ticks) (pos? ticks))
     (throw (ex-info ":ticks must be a positive integer" {:ticks ticks})))
   (let [worker-before (game/worker-status)]
     (when-not (:started worker-before)
       (throw (ex-info "Start the native workers with `game/start-headless!`"
                       {:worker worker-before})))
     (when reset?
       (simulation/set-race-seed! seed)
       (simulation/reset!))
     (let [started (System/nanoTime)
           samples
           (loop [tick 0
                  durations (transient [])]
             (if (= tick ticks)
               (persistent! durations)
               (let [deadline (+ started (* (inc tick) frame-nanos))
                     remaining (- deadline (System/nanoTime))]
                 (when (pos? remaining)
                   (java.util.concurrent.locks.LockSupport/parkNanos remaining))
                 (let [step-started (System/nanoTime)]
                   (simulation/step!)
                   (recur (inc tick)
                          (conj! durations
                                 (/ (- (System/nanoTime) step-started)
                                    1000000.0)))))))
           elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
           race (az/value (simulation/snapshot))
           cognition (game/cognition-status)
           worker-after (game/worker-status)
           request-delta (mapv - (:requests_by_actor worker-after)
                               (:requests_by_actor worker-before))
           result-delta (mapv - (:results_by_actor worker-after)
                              (:results_by_actor worker-before))]
       {:ticks ticks
        :elapsed-ms elapsed-ms
        :effective-hz (/ (* ticks 1000.0) elapsed-ms)
        :native-step-ms (distribution samples)
        :native-steps-over-budget (count (filter #(> % 8.333333) samples))
        :race (select-keys race
                           [:tick :state :finished :decisions :urgent_decisions
                            :deadline_misses :max_intent_age_ticks])
        :cognition (select-keys cognition
                                [:total_entries :llm_entries :fallback_entries
                                 :accepted_entries :rejected_entries
                                 :deadline_misses :average_total_us])
        :requests-by-racer request-delta
        :results-by-racer result-delta
        :worker (select-keys worker-after
                             [:started :running :threads :pending
                              :state_bytes])}))))
