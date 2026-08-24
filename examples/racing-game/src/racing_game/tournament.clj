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
   :max-ticks 8000
   :chunk-ticks 240
   :mode :fallback})

(def default-paired-options
  "One real paired seed is deliberately the default because live evaluation is
  paced at 120 Hz. Pass more seeds explicitly for a longer bake-off."
  {:seeds [0]
   :max-ticks 8000
   :chunk-ticks 240})

(def persona-names
  {0 :cautious
   1 :balanced
   2 :bold})

(def expected-routine-pace
  {:cautious :steady
   :balanced :attack
   :bold :maximum})

(defn- mean
  [values]
  (let [values (vec values)]
    (if (seq values)
      (/ (double (reduce + values)) (count values))
      0.0)))

(defn- sum
  [values]
  (reduce + 0 values))

(defn- frequencies-sorted
  [values]
  (into (sorted-map) (frequencies values)))

(defn- merge-frequencies
  [frequency-maps]
  (reduce #(merge-with + %1 %2) (sorted-map) frequency-maps))

(defn- pace-for-speed
  [speed]
  (cond
    (< speed 0.076) :steady
    (< speed 0.084) :attack
    :else :maximum))

(defn- lane-for-target
  [lane]
  (cond
    (< lane -0.025) :left
    (> lane 0.025) :right
    :else :center))

(defn- behavior-summary
  "Summarize only decoded installed actions. No prompt bytes or token IDs are
  read into a tournament report."
  [racer-id]
  (let [entries (filterv :accepted
                         (core/decision-logs racer-id
                                             telemetry/entries-per-racer))
        model-entries (filterv #(= telemetry/source-llm (:source %)) entries)
        routine-model
        (filterv #(and (zero? (:item %)) (not (:urgent %))) model-entries)]
    {:retained-decisions (count entries)
     :model-decisions (count model-entries)
     :routine-model-decisions (count routine-model)
     :paces (frequencies-sorted (map #(pace-for-speed (:target_speed %))
                                      model-entries))
     :routine-paces
     (frequencies-sorted (map #(pace-for-speed (:target_speed %))
                              routine-model))
     :lanes (frequencies-sorted (map #(lane-for-target (:lane_target %))
                                     model-entries))
     :item-actions
     (frequencies-sorted (map #(if (zero? (:action %)) :hold :use)
                              model-entries))}))

(defn- combine-behavior
  [entries]
  {:retained-decisions (sum (map #(get-in % [:behavior :retained-decisions])
                                  entries))
   :model-decisions (sum (map #(get-in % [:behavior :model-decisions])
                              entries))
   :routine-model-decisions
   (sum (map #(get-in % [:behavior :routine-model-decisions]) entries))
   :paces (merge-frequencies (map #(get-in % [:behavior :paces]) entries))
   :routine-paces
   (merge-frequencies (map #(get-in % [:behavior :routine-paces]) entries))
   :lanes (merge-frequencies (map #(get-in % [:behavior :lanes]) entries))
   :item-actions
   (merge-frequencies (map #(get-in % [:behavior :item-actions]) entries))})

(defn- dominant-key
  [frequencies]
  (when (seq frequencies)
    (->> frequencies
         (sort-by (fn [[key count]] [(- count) (name key)]))
         ffirst)))

(defn persona-evaluation
  "Measure model-driven routine behavior by persona from tournament races.
  A persona is competent only when it has real model samples and its expected
  routine pace is the most frequent installed pace."
  [races]
  (let [entries (mapcat :standings races)
        personas
        (into
         (sorted-map)
         (for [[_ persona] persona-names
               :let [persona-entries (filter #(= persona (:persona %)) entries)
                     behavior (combine-behavior persona-entries)
                     routine-paces (:routine-paces behavior)
                     routine-count (:routine-model-decisions behavior)
                     expected (get expected-routine-pace persona)
                     dominant (dominant-key routine-paces)]]
           [persona
            {:racers (count persona-entries)
             :expected-routine-pace expected
             :dominant-routine-pace dominant
             :routine-model-decisions routine-count
             :expected-pace-share
             (if (pos? routine-count)
               (/ (double (get routine-paces expected 0)) routine-count)
               0.0)
             :competent? (and (pos? routine-count) (= expected dominant))
             :behavior behavior}]))
        dominant-paces (keep :dominant-routine-pace (vals personas))
        model-evidence? (every? pos? (map :routine-model-decisions
                                          (vals personas)))]
    {:model-evidence? model-evidence?
     :all-competent? (and model-evidence?
                          (every? :competent? (vals personas)))
     :differentiated? (and model-evidence?
                           (= (count personas) (count (set dominant-paces))))
     :personas personas}))

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
    :or {seed 0 max-ticks 8000 chunk-ticks 240 mode :fallback}}]
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
                          (telemetry/racer-outcome-summary racer-id))
                         :persona
                         (get persona-names
                              (:persona
                               (az/value
                                (simulation/current-observation racer-id)))
                              :unknown)
                         :behavior (behavior-summary racer-id)})))
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
       :action-head-training-revision protocol/action-head-training-revision
       :tokenizer-version protocol/tokenizer-version
       :quantization-version protocol/quantization-version
       :quantization-format protocol/quantization-format
       :training-data-fingerprint protocol/training-data-fingerprint}
      :mode (:mode options)
      :seeds (vec seeds)
      :race-count (count races)
      :complete-races (count (filter :complete races))
      :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
      :scoreboard scoreboard
      :persona-evaluation (persona-evaluation races)
      :races races})))

(defn report-summary
  "Reduce a tournament report to comparable behavior and runtime outcomes."
  [report]
  (let [races (:races report)
        standings (mapcat :standings races)
        outcomes (map :outcomes standings)
        item-uses (sum (map #(get-in % [:race :items_used]) races))
        hits (sum (map #(get-in % [:race :hits]) races))
        decisions (sum (map :decisions standings))
        resolved (sum (map :resolved_decisions outcomes))]
    {:mode (:mode report)
     :race-count (:race-count report)
     :complete-races (:complete-races report)
     :average-finish-tick (mean (map :finish_tick standings))
     :decisions decisions
     :resolved-decisions resolved
     :item-uses item-uses
     :hits hits
     :hits-per-item (if (pos? item-uses)
                      (/ (double hits) item-uses)
                      0.0)
     :contacts (sum (map #(get-in % [:race :contacts]) races))
     :hazards-spawned (sum (map #(get-in % [:race :hazards_spawned]) races))
     :invalid-decisions
     (sum (map #(get-in % [:race :invalid_decisions]) races))
     :deadline-misses
     (sum (map #(get-in % [:cognition :deadline_misses]) races))
     :llm-decisions (sum (map #(get-in % [:cognition :llm_entries]) races))
     :fallback-decisions
     (sum (map #(get-in % [:cognition :fallback_entries]) races))
     :average-progress-gain
     (if (pos? resolved)
       (/ (double (sum (map :total_progress_gain outcomes))) resolved)
       0.0)}))

(defn- races-by-seed
  [report]
  (let [indexed (into {} (map (juxt :seed identity)) (:races report))]
    (when-not (= (count indexed) (count (:races report)))
      (throw (ex-info "Tournament report contains duplicate seeds"
                      {:seeds (mapv :seed (:races report))})))
    indexed))

(defn- standings-by-racer
  [race]
  (into {} (map (juxt :id identity)) (:standings race)))

(defn- paired-racer-row
  [seed racer baseline llm]
  {:seed seed
   :racer racer
   ;; Positive rank improvement means the LLM finished closer to first.
   :rank-improvement (- (:rank baseline) (:rank llm))
   ;; Negative finish delta means the LLM finished sooner.
   :finish-tick-delta (- (:finish_tick llm) (:finish_tick baseline))
   :decision-delta (- (:decisions llm) (:decisions baseline))
   :item-use-delta (- (get-in llm [:outcomes :item_uses])
                      (get-in baseline [:outcomes :item_uses]))
   :hit-delta (- (get-in llm [:outcomes :hits])
                 (get-in baseline [:outcomes :hits]))
   :progress-gain-delta
   (- (get-in llm [:outcomes :total_progress_gain])
      (get-in baseline [:outcomes :total_progress_gain]))})

(defn compare-reports
  "Compare transparent fallback and live-LLM reports over identical seeds.

  The result intentionally keeps separate dimensions instead of hiding gameplay
  behind one arbitrary score. Positive `:rank-improvement` and negative
  `:finish-tick-delta` favor the LLM."
  [baseline-report llm-report]
  (let [baseline-races (races-by-seed baseline-report)
        llm-races (races-by-seed llm-report)
        seeds (vec (sort (keys baseline-races)))]
    (when-not (= (set (keys baseline-races)) (set (keys llm-races)))
      (throw (ex-info "Paired tournaments must use identical seeds"
                      {:baseline-seeds (vec (sort (keys baseline-races)))
                       :llm-seeds (vec (sort (keys llm-races)))})))
    (let [rows
          (mapv
           (fn [[seed racer]]
             (let [baseline (get (standings-by-racer
                                  (get baseline-races seed)) racer)
                   llm (get (standings-by-racer (get llm-races seed)) racer)]
               (when-not (and baseline llm)
                 (throw (ex-info "Paired race is missing a racer"
                                 {:seed seed :racer racer})))
               (paired-racer-row seed racer baseline llm)))
           (for [seed seeds racer (range 8)] [seed racer]))
          per-racer
          (mapv
           (fn [racer]
             (let [entries (filter #(= racer (:racer %)) rows)]
               {:racer racer
                :races (count entries)
                :average-rank-improvement
                (mean (map :rank-improvement entries))
                :average-finish-tick-delta
                (mean (map :finish-tick-delta entries))
                :item-use-delta (sum (map :item-use-delta entries))
                :hit-delta (sum (map :hit-delta entries))}))
           (range 8))
          baseline-summary (report-summary baseline-report)
          llm-summary (report-summary llm-report)]
      {:seeds seeds
       :paired-races (count seeds)
       :baseline baseline-summary
       :llm llm-summary
       :deltas
       {:average-finish-tick
        (- (:average-finish-tick llm-summary)
           (:average-finish-tick baseline-summary))
        :item-uses (- (:item-uses llm-summary) (:item-uses baseline-summary))
        :hits (- (:hits llm-summary) (:hits baseline-summary))
        :contacts (- (:contacts llm-summary) (:contacts baseline-summary))
        :invalid-decisions
        (- (:invalid-decisions llm-summary)
           (:invalid-decisions baseline-summary))
        :deadline-misses
        (- (:deadline-misses llm-summary)
           (:deadline-misses baseline-summary))}
       :faster-finishes (count (filter #(neg? (:finish-tick-delta %)) rows))
       :equal-finishes (count (filter #(zero? (:finish-tick-delta %)) rows))
       :slower-finishes (count (filter #(pos? (:finish-tick-delta %)) rows))
       :per-racer per-racer
       :rows rows})))

(defn run-paired!
  "Run the transparent controller and real Granite policy over identical seeds.

  This owns the temporary headless workers and refuses to disturb an already
  running development session. Live races remain paced at wall-clock 120 Hz."
  ([]
   (run-paired! default-paired-options))
  ([options]
   (let [options (merge default-paired-options options)
         workers (core/worker-status)]
     (when (:started workers)
       (throw (ex-info "Paired evaluation will not take over live workers"
                       {:worker workers})))
     (let [baseline (run! (assoc options :mode :fallback))]
       (core/start-headless!)
       (try
         (let [llm (run! (assoc options :mode :live))]
           {:comparison (compare-reports baseline llm)
            :baseline-report baseline
            :llm-report llm})
         (finally
           (core/stop-headless!)))))))
