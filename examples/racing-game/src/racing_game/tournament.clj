(ns racing-game.tournament
  "Deterministic native race evaluation and per-racer outcome scoring."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.zig :as az]
            [racing-game.core :as core]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]))

(def default-options
  {:seeds (range 8)
   :max-ticks 7200
   :chunk-ticks 240
   :mode :fallback})

(defn- complete?
  []
  (= 8 (:finished (az/value (simulation/snapshot)))))

(defn- advance-fallback!
  [max-ticks chunk-ticks]
  (loop [advanced 0]
    (if (or (>= advanced max-ticks) (complete?))
      advanced
      (let [ticks (min chunk-ticks (- max-ticks advanced))]
        (simulation/step-many! ticks)
        (recur (+ advanced ticks))))))

(defn- advance-live!
  [max-ticks]
  (loop [advanced 0
         deadline (System/nanoTime)]
    (if (or (>= advanced max-ticks) (complete?))
      advanced
      (let [next-deadline (+ deadline 8333333)
            remaining (- next-deadline (System/nanoTime))]
        (when (pos? remaining)
          (java.util.concurrent.locks.LockSupport/parkNanos remaining))
        (simulation/step!)
        (recur (inc advanced) next-deadline)))))

(defn run-race!
  "Run one seeded native race. `:fallback` is accelerated and deterministic;
  `:live` respects 120 Hz wall time so the eight real inference workers can
  publish their decisions while the simulation remains responsive."
  [{:keys [seed max-ticks chunk-ticks mode]
    :or {seed 0 max-ticks 7200 chunk-ticks 240 mode :fallback}}]
  (let [workers (core/worker-status)]
    (when (and (= mode :fallback) (:started workers))
      (throw (ex-info "Fallback tournament requires stopped inference workers"
                      {:mode mode :worker workers})))
    (when (and (= mode :live) (not (:started workers)))
      (throw (ex-info "Live tournament requires `core/start-headless!` first"
                      {:mode mode :worker workers})))
    (simulation/set-race-seed! seed)
    (simulation/reset!)
    (let [advanced (case mode
                     :fallback (advance-fallback! max-ticks chunk-ticks)
                     :live (advance-live! max-ticks)
                     (throw (ex-info "Unknown tournament mode"
                                     {:mode mode
                                      :supported #{:fallback :live}})))
          race (az/value (simulation/snapshot))
          standings
          (->> (range 8)
               (mapv (fn [racer-id]
                       (merge
                        (select-keys
                         (az/value (simulation/racer-view racer-id))
                         [:id :rank :finished :finish_tick :decisions
                          :urgent_decisions :invalid_decisions
                          :average_latency_us])
                        {:outcomes
                         (az/value
                          (telemetry/racer-outcome-summary racer-id))})))
               (sort-by :rank)
               vec)]
      {:seed seed
       :mode mode
       :advanced-ticks advanced
       :complete (= 8 (:finished race))
       :race race
       :cognition (core/cognition-status)
       :standings standings})))

(defn- scoreboard-row
  [racer-id races]
  (let [entries (map #(first (filter (fn [racer]
                                      (= racer-id (:id racer)))
                                    (:standings %)))
                     races)
        ranks (map :rank entries)
        outcomes (map :outcomes entries)
        race-count (count entries)]
    {:racer racer-id
     :races race-count
     :wins (count (filter #(= 1 %) ranks))
     :podiums (count (filter #(<= % 3) ranks))
     :points (reduce + (map #(- 9 %) ranks))
     :average-rank (/ (double (reduce + ranks)) race-count)
     :average-finish-tick
     (/ (double (reduce + (map :finish_tick entries))) race-count)
     :decisions (reduce + (map :decisions entries))
     :resolved-decisions (reduce + (map :resolved_decisions outcomes))
     :item-uses (reduce + (map :item_uses outcomes))
     :hits (reduce + (map :hits outcomes))
     :average-progress-gain
     (/ (double (reduce + (map :total_progress_gain outcomes)))
        (max 1 (reduce + (map :resolved_decisions outcomes))))
     :average-rank-gain
     (/ (double (reduce + (map :total_rank_gain outcomes)))
        (max 1 (reduce + (map :resolved_decisions outcomes))))}))

(defn run!
  "Evaluate the current hot-published policy across deterministic seed
  permutations and return a sortable JVM report backed by native races."
  ([]
   (run! default-options))
  ([options]
   (az/await!)
   (let [{:keys [seeds] :as options} (merge default-options options)
         started (System/nanoTime)
         races (mapv #(run-race! (assoc options :seed %)) seeds)
         scoreboard (->> (range 8)
                         (mapv #(scoreboard-row % races))
                         (sort-by (juxt (comp - :points)
                                       :average-finish-tick
                                       :racer))
                         vec)]
     {:provenance
      {:model-fingerprint protocol/model-fingerprint
       :action-head-fingerprint protocol/action-head-fingerprint
       :action-head-training-revision protocol/action-head-training-revision}
      :mode (:mode options)
      :seeds (vec seeds)
      :race-count (count races)
      :complete-races (count (filter :complete races))
      :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
      :scoreboard scoreboard
      :races races})))
