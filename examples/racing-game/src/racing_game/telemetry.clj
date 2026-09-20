(ns racing-game.telemetry
  "Bounded, allocation-free logs for inspecting every independent racer mind."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [racing-game.protocol :as protocol]))

(az/defconst racer-count :usize protocol/racer-count)

(az/defconst entries-per-racer :usize 64)

(az/defconst input-token-capacity :usize 64)

(az/defconst output-token-capacity :usize 16)

(az/defconst prompt-byte-capacity :usize 384)

(az/defconst response-byte-capacity :usize 96)

(az/defconst source-fallback :u8 0)

(az/defconst source-llm :u8 1)

(az/defconst source-replay :u8 2)

(az/defconst source-human :u8 3)

(az/defconst outcome-window-ticks :u64 120)

(az/defn outcome-window-seconds :f32
  "Expose the causal evaluation horizon to nREPL monitors and tooling."
  []
  1.0)

(az/defstruct DecisionLog
  "One complete, Clojure-readable cognition event from observation to intent."
  {:layout :extern}
  [[:valid :bool]
   [:racer_id :u8]
   [:source :u8]
   [:accepted :bool]
   [:urgent :bool]
   [:prompt_truncated :bool]
   [:response_truncated :bool]
   [:rank :u8]
   [:item :u8]
   [:target :u8]
   [:action :u8]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:validation_code :u8]
   [:deadline_status :u8]
   [:lap :u16]
   [:input_token_count :u16]
   [:output_token_count :u16]
   [:prompt_byte_count :u16]
   [:response_byte_count :u16]
   [:tokenizer_version :u16]
   [:quantization_version :u16]
   [:quantization_format :u8]
   [:action_head_training_revision :u32]
   [:training_data_fingerprint :u64]
   [:training_data_sha256 [:array 32 :u8]]
   [:model_fingerprint :u64]
   [:action_head_fingerprint :u64]
   [:sampler_state :u64]
   [:revision :u64]
   [:race_epoch :u64]
   [:enqueue_tick :u64]
   [:install_tick :u64]
   [:simulation_tick :u64]
   [:queue_us :u64]
   [:prefill_us :u64]
   [:decode_us :u64]
   [:total_us :u64]
   [:tokens_per_second :f32]
   [:progress :f32]
   [:speed :f32]
   [:lane_target :f32]
   [:target_speed :f32]
   [:input_tokens [:array 64 :u32]]
   [:output_tokens [:array 16 :u32]]
   [:prompt_bytes [:array 384 :u8]]
   [:response_bytes [:array 96 :u8]]])

(az/defstruct DecisionOutcome
  "Bounded causal result for one decision over a fixed one-second horizon."
  {:layout :extern}
  [[:valid :bool]
   [:resolved :bool]
   [:item_used :bool]
   [:racer_id :u8]
   [:start_rank :u8]
   [:end_rank :u8]
   [:hits_dealt :u16]
   [:revision :u64]
   [:start_tick :u64]
   [:resolved_tick :u64]
   [:start_absolute_progress :f32]
   [:progress_gain :f32]
   [:rank_gain :i8]])

(az/defstruct TelemetrySummary
  "Aggregate native observability for nREPL and the optional ImGui monitor."
  {:layout :extern}
  [[:total_entries :u64]
   [:llm_entries :u64]
   [:fallback_entries :u64]
   [:replay_entries :u64]
   [:accepted_entries :u64]
   [:rejected_entries :u64]
   [:urgent_entries :u64]
   [:deadline_misses :u64]
   [:resolved_outcomes :u64]
   [:attributed_item_uses :u64]
   [:attributed_hits :u64]
   [:average_total_us :u64]
   [:average_tokens_per_second :f32]
   [:average_progress_gain :f32]
   [:average_rank_gain :f32]])

(az/defstruct RacerOutcomeSummary
  "Lifetime decision outcomes for one racer in the current race."
  {:layout :extern}
  [[:valid :bool]
   [:racer_id :u8]
   [:resolved_decisions :u64]
   [:item_uses :u64]
   [:hits :u64]
   [:total_progress_gain :f32]
   [:total_rank_gain :i64]
   [:average_progress_gain :f32]
   [:average_rank_gain :f32]])

(az/defvar decision-logs [:array (* racer-count entries-per-racer) DecisionLog]
  (std-mem/zeroes (az/type [:array (* racer-count entries-per-racer) DecisionLog])))

(az/defvar decision-outcomes [:array (* racer-count entries-per-racer) DecisionOutcome]
  (std-mem/zeroes (az/type [:array (* racer-count entries-per-racer) DecisionOutcome])))

(az/defvar decision-counts [:array racer-count :u64]
  (std-mem/zeroes (az/type [:array racer-count :u64])))

(az/defvar resolved-outcome-counts [:array racer-count :u64]
  (std-mem/zeroes (az/type [:array racer-count :u64])))

(az/defvar attributed-item-use-counts [:array racer-count :u64]
  (std-mem/zeroes (az/type [:array racer-count :u64])))

(az/defvar attributed-hit-counts [:array racer-count :u64]
  (std-mem/zeroes (az/type [:array racer-count :u64])))

(az/defvar total-progress-gains [:array racer-count :f32]
  (std-mem/zeroes (az/type [:array racer-count :f32])))

(az/defvar total-rank-gains [:array racer-count :i64]
  (std-mem/zeroes (az/type [:array racer-count :i64])))

(az/defn empty-log DecisionLog
  []
  (std-mem/zeroes (az/type DecisionLog)))

(az/defn reset! :void
  "Clear all actor histories without touching race or model state."
  []
  (set! decision-logs
        (std-mem/zeroes (az/type [:array (* racer-count entries-per-racer) DecisionLog])))
  (set! decision-outcomes
        (std-mem/zeroes (az/type [:array (* racer-count entries-per-racer) DecisionOutcome])))
  (set! decision-counts
        (std-mem/zeroes (az/type [:array racer-count :u64])))
  (set! resolved-outcome-counts
        (std-mem/zeroes (az/type [:array racer-count :u64])))
  (set! attributed-item-use-counts
        (std-mem/zeroes (az/type [:array racer-count :u64])))
  (set! attributed-hit-counts
        (std-mem/zeroes (az/type [:array racer-count :u64])))
  (set! total-progress-gains
        (std-mem/zeroes (az/type [:array racer-count :f32])))
  (set! total-rank-gains
        (std-mem/zeroes (az/type [:array racer-count :i64]))))

(az/defn record! :void
  "Append one already-bounded decision event to its racer's ring."
  [[entry DecisionLog]]
  (when (< (az/field entry racer_id) racer-count)
    (let [racer-index (ak/as (ak/intCast (az/field entry racer_id)) :usize)
          sequence (az/index decision-counts racer-index)
          slot (+ (* racer-index entries-per-racer)
                  (ak/as (ak/intCast (mod sequence entries-per-racer))
                         :usize))]
      (set! (az/index decision-logs slot) entry)
      (set! (az/index decision-outcomes slot)
            (DecisionOutcome
             {:valid true :resolved false :item_used false
              :racer_id (az/field entry racer_id)
              :start_rank (az/field entry rank) :end_rank (az/field entry rank)
              :hits_dealt 0 :revision (az/field entry revision)
              :start_tick (az/field entry install_tick) :resolved_tick 0
              :start_absolute_progress
              (+ (ak/as (ak/floatFromInt (az/field entry lap)) :f32)
                 (az/field entry progress))
              :progress_gain 0.0 :rank_gain 0}))
      (set! (az/index decision-counts racer-index) (+ sequence 1)))))

(az/defn record-llm! :void
  "Attach bounded prompt, response, and token previews to an LLM decision.
  Counts retain the full lengths while truncation flags make omitted tails
  explicit. The game thread never allocates while recording."
  [[base DecisionLog]
   [prompt [:slice-const :u8]]
   [response [:slice-const :u8]]
   [input-tokens [:slice-const :u32]]
   [output-tokens [:slice-const :u32]]]
  (let [^:var entry base
        prompt-count (ak/min (az/field prompt len) prompt-byte-capacity)
        response-count (ak/min (az/field response len) response-byte-capacity)
        input-count (ak/min (az/field input-tokens len) input-token-capacity)
        output-count (ak/min (az/field output-tokens len) output-token-capacity)]
    (set! (az/field entry source) source-llm)
    (set! (az/field entry prompt_truncated)
          (> (az/field prompt len) prompt-byte-capacity))
    (set! (az/field entry response_truncated)
          (> (az/field response len) response-byte-capacity))
    (set! (az/field entry prompt_byte_count) (ak/intCast prompt-count))
    (set! (az/field entry response_byte_count) (ak/intCast response-count))
    (set! (az/field entry input_token_count)
          (ak/intCast (az/field input-tokens len)))
    (set! (az/field entry output_token_count)
          (ak/intCast (az/field output-tokens len)))
    (dotimes [index prompt-count]
      (set! (az/index (az/field entry prompt_bytes) index)
            (az/index prompt index)))
    (dotimes [index response-count]
      (set! (az/index (az/field entry response_bytes) index)
            (az/index response index)))
    (dotimes [index input-count]
      (set! (az/index (az/field entry input_tokens) index)
            (az/index input-tokens index)))
    (dotimes [index output-count]
      (set! (az/index (az/field entry output_tokens) index)
            (az/index output-tokens index)))
    (record! entry)))

(az/defn record-fallback! :void
  "Record the transparent policy using the same schema as future LLM results."
  [[racer-id :u8]
   [rank :u8]
   [lap :u16]
   [item :u8]
   [target :u8]
   [action :u8]
   [urgent :bool]
   [deadline-status :u8]
   [revision :u64]
   [race-epoch :u64]
   [simulation-tick :u64]
   [progress :f32]
   [speed :f32]
   [lane-target :f32]
   [target-speed :f32]]
  (record!
   (DecisionLog
    {:valid true :racer_id racer-id :source source-fallback
     :accepted true :urgent urgent :prompt_truncated false
     :response_truncated false :rank rank :item item :target target
     :action action
     :observation_schema protocol/observation-schema-version
     :action_schema protocol/action-schema-version
     :validation_code 0 :deadline_status deadline-status :lap lap
     :input_token_count 0 :output_token_count 0 :prompt_byte_count 0
     :response_byte_count 0
     :tokenizer_version protocol/tokenizer-version
     :quantization_version protocol/quantization-version
     :quantization_format protocol/quantization-format
     :action_head_training_revision protocol/action-head-training-revision
     :training_data_fingerprint protocol/training-data-fingerprint
     :training_data_sha256 protocol/training-data-sha256
     :model_fingerprint protocol/model-fingerprint
     :action_head_fingerprint protocol/action-head-fingerprint
     :sampler_state 0
     :revision revision :race_epoch race-epoch
     :enqueue_tick simulation-tick :install_tick simulation-tick
     :simulation_tick simulation-tick
     :queue_us 0 :prefill_us 0 :decode_us 0 :total_us 0
     :tokens_per_second 0.0 :progress progress :speed speed
     :lane_target lane-target :target_speed target-speed
     :input_tokens (std-mem/zeroes (az/type [:array 64 :u32]))
     :output_tokens (std-mem/zeroes (az/type [:array 16 :u32]))
     :prompt_bytes (std-mem/zeroes (az/type [:array 384 :u8]))
     :response_bytes (std-mem/zeroes (az/type [:array 96 :u8]))})))

(az/defn decision-count :u64
  "Return the monotonic number of logged decisions for one racer."
  [[racer-id :u8]]
  (if (< racer-id racer-count)
    (az/index decision-counts (ak/intCast racer-id))
    0))

(az/defn entry-at DecisionLog
  "Return `offset` decisions back from a racer's newest entry."
  [[racer-id :u8]
   [offset :usize]]
  (if (>= racer-id racer-count)
    (empty-log)
    (let [racer-index (ak/as (ak/intCast racer-id) :usize)
          count (az/index decision-counts racer-index)
          available (ak/min count entries-per-racer)]
      (if (>= offset available)
        (empty-log)
        (let [sequence (- count 1 offset)
              slot (+ (* racer-index entries-per-racer)
                      (ak/as (ak/intCast (mod sequence entries-per-racer))
                             :usize))]
          (az/index decision-logs slot))))))

(az/defn latest DecisionLog
  "Return the newest complete cognition event for one racer."
  [[racer-id :u8]]
  (entry-at racer-id 0))

(az/defn outcome-at DecisionOutcome
  "Return the causal outcome aligned with `entry-at`."
  [[racer-id :u8]
   [offset :usize]]
  (if (>= racer-id racer-count)
    (std-mem/zeroes (az/type DecisionOutcome))
    (let [racer-index (ak/as (ak/intCast racer-id) :usize)
          count (az/index decision-counts racer-index)
          available (ak/min count entries-per-racer)]
      (if (>= offset available)
        (std-mem/zeroes (az/type DecisionOutcome))
        (let [sequence (- count 1 offset)
              slot (+ (* racer-index entries-per-racer)
                      (ak/as (ak/intCast (mod sequence entries-per-racer))
                             :usize))]
          (az/index decision-outcomes slot))))))

(az/defn mark-item-used! :bool
  "Attribute an item consumption to the exact decision that requested it."
  [[racer-id :u8]
   [revision :u64]]
  (let [^:var found (ak/bool false)]
    (when (< racer-id racer-count)
      (let [racer-index (ak/as (ak/intCast racer-id) :usize)
            available (ak/as (ak/intCast
                              (ak/min (az/index decision-counts racer-index)
                                      entries-per-racer))
                             :usize)]
        (dotimes [offset available]
          (when (ak/! found)
            (let [sequence (- (az/index decision-counts racer-index) 1 offset)
                  slot (+ (* racer-index entries-per-racer)
                          (ak/as (ak/intCast (mod sequence entries-per-racer))
                                 :usize))]
              (when (ak/== (az/field (az/index decision-outcomes slot) revision)
                           revision)
                (when (ak/! (az/field (az/index decision-outcomes slot)
                                     item_used))
                  (set! (az/field (az/index decision-outcomes slot) item_used) true)
                  (set! (az/index attributed-item-use-counts racer-index)
                        (+ (az/index attributed-item-use-counts racer-index) 1)))
                (set! found true)))))))
    found))

(az/defn mark-hit! :bool
  "Attribute one unshielded hit to the decision that launched the attack."
  [[racer-id :u8]
   [revision :u64]]
  (let [^:var found (ak/bool false)]
    (when (< racer-id racer-count)
      (let [racer-index (ak/as (ak/intCast racer-id) :usize)
            available (ak/as (ak/intCast
                              (ak/min (az/index decision-counts racer-index)
                                      entries-per-racer))
                             :usize)]
        (dotimes [offset available]
          (when (ak/! found)
            (let [sequence (- (az/index decision-counts racer-index) 1 offset)
                  slot (+ (* racer-index entries-per-racer)
                          (ak/as (ak/intCast (mod sequence entries-per-racer))
                                 :usize))]
              (when (ak/== (az/field (az/index decision-outcomes slot) revision)
                           revision)
                (set! (az/field (az/index decision-outcomes slot) hits_dealt)
                      (+ (az/field (az/index decision-outcomes slot) hits_dealt) 1))
                (set! (az/index attributed-hit-counts racer-index)
                      (+ (az/index attributed-hit-counts racer-index) 1))
                (set! found true)))))))
    found))

(az/defn resolve-due-outcomes! :void
  "Resolve every retained decision whose fixed evaluation horizon has elapsed."
  [[racer-id :u8]
   [simulation-tick :u64]
   [rank :u8]
   [lap :u16]
   [progress :f32]
   [finished :bool]]
  (when (< racer-id racer-count)
    (let [racer-index (ak/as (ak/intCast racer-id) :usize)
          count (az/index decision-counts racer-index)
          available (ak/as (ak/intCast (ak/min count entries-per-racer))
                           :usize)
          absolute-progress
          (+ (ak/as (ak/floatFromInt lap) :f32) progress)]
      (dotimes [offset available]
        (let [sequence (- count 1 offset)
              slot (+ (* racer-index entries-per-racer)
                      (ak/as (ak/intCast (mod sequence entries-per-racer))
                             :usize))]
          (when (and (az/field (az/index decision-outcomes slot) valid)
                     (ak/! (az/field (az/index decision-outcomes slot) resolved))
                     (or finished
                         (>= simulation-tick
                             (+ (az/field (az/index decision-outcomes slot)
                                          start_tick)
                                outcome-window-ticks))))
            (set! (az/field (az/index decision-outcomes slot) resolved) true)
            (set! (az/field (az/index decision-outcomes slot) end_rank) rank)
            (set! (az/field (az/index decision-outcomes slot) resolved_tick)
                  simulation-tick)
            (set! (az/field (az/index decision-outcomes slot) progress_gain)
                  (- absolute-progress
                     (az/field (az/index decision-outcomes slot)
                               start_absolute_progress)))
            (set! (az/field (az/index decision-outcomes slot) rank_gain)
                  (- (ak/as (ak/intCast
                             (az/field (az/index decision-outcomes slot)
                                       start_rank))
                            :i8)
                     (ak/as (ak/intCast rank) :i8)))
            (set! (az/index resolved-outcome-counts racer-index)
                  (+ (az/index resolved-outcome-counts racer-index) 1))
            (set! (az/index total-progress-gains racer-index)
                  (+ (az/index total-progress-gains racer-index)
                     (az/field (az/index decision-outcomes slot)
                               progress_gain)))
            (set! (az/index total-rank-gains racer-index)
                  (+ (az/index total-rank-gains racer-index)
                     (ak/as (ak/intCast
                             (az/field (az/index decision-outcomes slot)
                                       rank_gain))
                            :i64)))))))))

(az/defn racer-outcome-summary RacerOutcomeSummary
  "Return complete current-race outcome totals independent of ring eviction."
  [[racer-id :u8]]
  (if (>= racer-id racer-count)
    (RacerOutcomeSummary
     {:valid false :racer_id racer-id :resolved_decisions 0 :item_uses 0
      :hits 0 :total_progress_gain 0.0 :total_rank_gain 0
      :average_progress_gain 0.0 :average_rank_gain 0.0})
    (let [index (ak/as (ak/intCast racer-id) :usize)
          resolved (az/index resolved-outcome-counts index)
          progress-gain (az/index total-progress-gains index)
          rank-gain (az/index total-rank-gains index)]
      (RacerOutcomeSummary
       {:valid true :racer_id racer-id :resolved_decisions resolved
        :item_uses (az/index attributed-item-use-counts index)
        :hits (az/index attributed-hit-counts index)
        :total_progress_gain progress-gain :total_rank_gain rank-gain
        :average_progress_gain
        (if (> resolved 0)
          (/ progress-gain (ak/as (ak/floatFromInt resolved) :f32))
          0.0)
        :average_rank_gain
        (if (> resolved 0)
          (/ (ak/as (ak/floatFromInt rank-gain) :f32)
             (ak/as (ak/floatFromInt resolved) :f32))
          0.0)}))))

(az/defn summary TelemetrySummary
  "Aggregate the bounded histories without allocating."
  []
  (let [^:var total (ak/u64 0)
        ^:var llm (ak/u64 0)
        ^:var fallback (ak/u64 0)
        ^:var replay (ak/u64 0)
        ^:var accepted (ak/u64 0)
        ^:var rejected (ak/u64 0)
        ^:var urgent (ak/u64 0)
        ^:var deadline-misses (ak/u64 0)
        ^:var resolved (ak/u64 0)
        ^:var item-uses (ak/u64 0)
        ^:var hits (ak/u64 0)
        ^:var total-us (ak/u64 0)
        ^:var total-tps (ak/f32 0.0)
        ^:var total-progress-gain (ak/f32 0.0)
        ^:var total-rank-gain (ak/f32 0.0)]
    (dotimes [racer-index racer-count]
      (let [count (ak/as (ak/intCast
                          (ak/min (az/index decision-counts racer-index)
                                  entries-per-racer))
                         :usize)]
        (dotimes [offset count]
          (let [entry (entry-at (ak/intCast racer-index) offset)
                outcome (outcome-at (ak/intCast racer-index) offset)]
            (when (az/field entry valid)
              (set! total (+ total 1))
              (cond
                (ak/== (az/field entry source) source-llm)
                (set! llm (+ llm 1))

                (ak/== (az/field entry source) source-replay)
                (set! replay (+ replay 1))

                :else
                (set! fallback (+ fallback 1)))
              (if (az/field entry accepted)
                (set! accepted (+ accepted 1))
                (set! rejected (+ rejected 1)))
              (when (az/field entry urgent)
                (set! urgent (+ urgent 1)))
              (when (> (az/field entry deadline_status) 0)
                (set! deadline-misses (+ deadline-misses 1)))
              (set! total-us (+ total-us (az/field entry total_us)))
              (set! total-tps (+ total-tps
                                 (az/field entry tokens_per_second)))
              (when (az/field outcome resolved)
                (set! resolved (+ resolved 1))
                (when (az/field outcome item_used)
                  (set! item-uses (+ item-uses 1)))
                (set! hits (+ hits (az/field outcome hits_dealt)))
                (set! total-progress-gain
                      (+ total-progress-gain (az/field outcome progress_gain)))
                (set! total-rank-gain
                      (+ total-rank-gain
                         (ak/as (ak/floatFromInt
                                 (az/field outcome rank_gain))
                                :f32)))))))))
    (TelemetrySummary
     {:total_entries total :llm_entries llm :fallback_entries fallback
      :replay_entries replay
      :accepted_entries accepted :rejected_entries rejected
      :urgent_entries urgent
      :deadline_misses deadline-misses
      :resolved_outcomes resolved
      :attributed_item_uses item-uses
      :attributed_hits hits
      :average_total_us (if (> total 0) (/ total-us total) 0)
      :average_tokens_per_second
      (if (> total 0)
        (/ total-tps (ak/as (ak/floatFromInt total) :f32))
        0.0)
      :average_progress_gain
      (if (> resolved 0)
        (/ total-progress-gain (ak/as (ak/floatFromInt resolved) :f32))
        0.0)
      :average_rank_gain
      (if (> resolved 0)
        (/ total-rank-gain (ak/as (ak/floatFromInt resolved) :f32))
        0.0)})))
