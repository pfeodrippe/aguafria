(ns racing-game.worker
  "Fixed native inference workers with one bounded mailbox per AI actor."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.Thread :as std-thread]
            [aguafria.std.c :as std-c]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as a]
            [racing-game.inference :as inference]
            [racing-game.protocol :as protocol]))

(a/defconst actor-count :usize protocol/actor-count)

(a/defconst racer-count :usize protocol/racer-count)

(a/defconst team-count :usize protocol/team-count)

(a/defconst actor-kind-driver :u8 0)

(a/defconst actor-kind-team :u8 1)

(a/defconst prompt-capacity :usize 160)

(a/defvar sampling-temperature :f32 0.35)

(a/defstruct SampledAction
  "One reproducible constrained draw and the exact post-draw RNG state."
  {:layout :extern}
  [[:code :u8]
   [:state :u64]])

(a/defstruct PromptBuffer
  "One bounded human-readable prompt owned by the request worker stack."
  {:layout :extern}
  [[:byte_count :u16]
   [:bytes [:array 160 :u8]]])

(a/defstruct InferenceRequest
  "Immutable observation published by the 120 Hz simulation."
  {:layout :extern}
  [[:valid :bool]
   [:actor_kind :u8]
   [:team :u8]
   [:racer :u8]
   [:rank :u8]
   [:lap :u16]
   [:item :u8]
   [:target :u8]
   [:persona :u8]
   [:target_distance :u8]
   [:target_lane :u8]
   [:tactical_status :u8]
   [:driver_a :u8]
   [:driver_b :u8]
   [:rank_a :u8]
   [:rank_b :u8]
   [:tire_a :u8]
   [:tire_b :u8]
   [:damage_a :u8]
   [:damage_b :u8]
   [:pit_a :u8]
   [:pit_b :u8]
   [:box_occupied :bool]
   [:urgent :bool]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:revision :u64]
   [:race_epoch :u64]
   [:simulation_tick :u64]
   [:progress :f32]
   [:speed :f32]
   [:enqueue_seconds :f64]])

(a/defstruct InferenceResult
  "One complete native LLM result ready for semantic installation."
  {:layout :extern}
  [[:valid :bool]
   [:accepted :bool]
   [:actor_kind :u8]
   [:team :u8]
   [:racer :u8]
   [:rank :u8]
   [:lap :u16]
   [:item :u8]
   [:urgent :bool]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:action_code :u8]
   [:item_action :u8]
   [:target :u8]
   [:sampler_state :u64]
   [:revision :u64]
   [:race_epoch :u64]
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
   [:prompt_byte_count :u16]
   [:input_token_count :u16]
   [:output_token_count :u16]
   [:best_token :u32]
   [:prompt_bytes [:array 160 :u8]]
   [:input_tokens [:array 160 :u32]]
   [:output_tokens [:array 1 :u32]]
   [:response_bytes [:array 1 :u8]]])

(a/defstruct WorkerSummary
  "Clojure-readable state for the real native worker and its mailboxes."
  {:layout :extern}
  [[:running :bool]
   [:started :bool]
   [:threads :u8]
   [:requests :u64]
   [:results :u64]
   [:idle_waits :u64]
   [:pending :u8]
   [:requests_by_actor [:array actor-count :u64]]
   [:results_by_actor [:array actor-count :u64]]
   [:state_bytes :usize]])

(a/defstruct LanguageRequest
  "Ordinary words plus simulation-owned identity/deadline. No action head.
  Fixed storage makes a submitted request immutable and independent of callers."
  {:layout :extern}
  [[:valid :bool] [:actor :u8] [:revision :u64] [:epoch :u64]
   [:observed_tick :u64] [:expires_tick :u64] [:enqueue_seconds :f64]
   [:system_byte_count :u16] [:prompt_byte_count :u16]
   [:system_bytes [:array 160 :u8]] [:prompt_bytes [:array 160 :u8]]])

(a/defstruct LanguageResult
  "A delivered reply, even when generation failed. Preserve the exact request,
  output and timings. Generation validity/EOS is checked by the installer."
  {:layout :extern}
  [[:valid :bool] [:request LanguageRequest] [:generation inference/LanguageGeneration]
   [:queue_us :u64] [:inference_us :u64] [:total_us :u64]])

(a/defvar language-requests [:array actor-count LanguageRequest]
  (std-mem/zeroes (a/type [:array actor-count LanguageRequest])))

(a/defvar language-results [:array actor-count LanguageResult]
  (std-mem/zeroes (a/type [:array actor-count LanguageResult])))

;; 0 empty, 1 queued, 2 computing, 3 complete, 4 reserved for copying.
;; An unread reply cannot be overwritten by a subsequent request.
(a/defvar language-mailbox-states [:array actor-count :u8]
  (std-mem/zeroes (a/type [:array actor-count :u8])))

(a/defvar requests [:array actor-count InferenceRequest]
  (std-mem/zeroes (a/type [:array actor-count InferenceRequest])))

(a/defvar request-revisions [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar consumed-revisions [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar results [:array actor-count InferenceResult]
  (std-mem/zeroes (a/type [:array actor-count InferenceResult])))

(a/defvar result-revisions [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar worker-running :u8 0)

(a/defvar worker-started :u8 0)

(a/defvar worker-threads [:array actor-count [:optional aguafria.std/Thread]]
  (std-mem/zeroes
   (a/type [:array actor-count [:optional aguafria.std/Thread]])))

(a/defvar worker-thread-count :u8 0)

(a/defvar request-count :u64 0)

(a/defvar result-count :u64 0)

(a/defvar request-counts [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar result-counts [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar sampler-states [:array actor-count :u64]
  (std-mem/zeroes (a/type [:array actor-count :u64])))

(a/defvar idle-wait-count :u64 0)

(a/defn- empty-result InferenceResult
  []
  (std-mem/zeroes (a/type InferenceResult)))

(a/defn- monotonic-seconds :f64
  "Read the operating system monotonic clock without depending on a windowing
  event loop. This is safe from desktop, headless nREPL, and worker threads."
  []
  (let [^:var timestamp (std-mem/zeroes (a/type std-c/timespec))
        result (std-c/clock_gettime :.MONOTONIC (ak/& timestamp))]
    (if (ak/== result 0)
      (+ (ak/as (ak/floatFromInt (a/field timestamp sec)) :f64)
         (/ (ak/as (ak/floatFromInt (a/field timestamp nsec)) :f64)
            1000000000.0))
      0.0)))

(a/defn- idle-wait! :void
  "Yield the worker core for half a millisecond without coupling it to an I/O
  runtime. Mailbox latency remains negligible beside one native LLM pass."
  []
  (let [^:var duration (ak/as (std-mem/zeroes (a/type std-c/timespec)) std-c/timespec)]
    (ak/= (a/field duration nsec) 500000)
    (ak/= :_ (std-c/nanosleep (ak/& duration) ak/null))))

(a/defn submit-language! :bool
  "Nonblocking bounded handoff. Busy actors retain their current request/result;
  this call never invokes inference and never changes a simulation body." [[request LanguageRequest]]
  (let [actor (ak/as (a/field request actor) :usize)]
    (when (or (>= actor actor-count) (ak/! (a/field request valid))
              (ak/== (a/field request revision) 0)
              (ak/== (a/field request system_byte_count) 0)
              (> (a/field request system_byte_count) 160)
              (ak/== (a/field request prompt_byte_count) 0)
              (> (a/field request prompt_byte_count) 160)
              (<= (a/field request expires_tick) (a/field request observed_tick))
              (ak/== (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0))
      (ak/return false))
    (let [state (ak/& (a/index language-mailbox-states actor))]
      (when (ak/!= (ak/cmpxchgStrong :u8 state 0 4 :.acq_rel :.acquire) ak/null)
        (ak/return false))
      (ak/= (a/index language-requests actor) request)
      (ak/= (a/field (a/index language-requests actor) enqueue_seconds) (monotonic-seconds))
      (ak/= :_ (ak/atomicRmw :u64 (ak/& request-count) :.Add 1 :.monotonic))
      (ak/= :_ (ak/atomicRmw :u64 (ak/& (a/index request-counts actor)) :.Add 1 :.monotonic))
      (ak/atomicStore :u8 state 1 :.release))
    true))

(a/defn- process-language-request! :bool
  "One actor's worker exclusively owns that actor's model sequence. Readable
  and legacy requests share the same thread, never concurrent model state." [[actor :usize]]
  (let [state (ak/& (a/index language-mailbox-states actor))]
    (when (ak/!= (ak/cmpxchgStrong :u8 state 1 2 :.acq_rel :.acquire) ak/null)
      (ak/return false))
    (let [request (a/index language-requests actor)
          started (monotonic-seconds)
          generation (inference/generate-language-with-system! actor
                       (ak/& (a/index (a/field request system_bytes) 0))
                       (a/field request system_byte_count)
                       (ak/& (a/index (a/field request prompt_bytes) 0))
                       (a/field request prompt_byte_count) 64)
          finished (monotonic-seconds)]
      (ak/= (a/index language-results actor)
        (LanguageResult {:valid true :request request :generation generation
          :queue_us (ak/intFromFloat (* (ak/max 0.0 (- started (a/field request enqueue_seconds))) 1000000.0))
          :inference_us (ak/intFromFloat (* (ak/max 0.0 (- finished started)) 1000000.0))
          :total_us (ak/intFromFloat (* (ak/max 0.0 (- finished (a/field request enqueue_seconds))) 1000000.0))}))
      (ak/= :_ (ak/atomicRmw :u64 (ak/& result-count) :.Add 1 :.monotonic))
      (ak/= :_ (ak/atomicRmw :u64 (ak/& (a/index result-counts actor)) :.Add 1 :.monotonic))
      (ak/atomicStore :u8 state 3 :.release))
    true))

(a/defn take-language-result! LanguageResult
  "Consume one fully published result once. Returns valid=false while pending.
  Copy reservation prevents another client submitting over an unread reply." [[actor :usize]]
  (when (>= actor actor-count)
    (ak/return (std-mem/zeroes (a/type LanguageResult))))
  (let [state (ak/& (a/index language-mailbox-states actor))]
    (when (ak/!= (ak/cmpxchgStrong :u8 state 3 4 :.acq_rel :.acquire) ak/null)
      (ak/return (std-mem/zeroes (a/type LanguageResult))))
    (let [result (a/index language-results actor)]
      (ak/atomicStore :u8 state 0 :.release)
      result)))

(a/defn language-mailbox-state :u8
  "Diagnostic state only: 0 idle, 1 queued, 2 thinking, 3 reply ready, 4 copying." [[actor :usize]]
  (if (< actor actor-count)
    (ak/atomicLoad :u8 (ak/& (a/index language-mailbox-states actor)) :.acquire)
    255))

(a/defn- persona-text [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 0) "cautious"
    (ak/== value 1) "balanced"
    :else "bold"))

(a/defn- item-text [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "bolt"
    (ak/== value 2) "trap"
    (ak/== value 3) "boost"
    (ak/== value 4) "shield"
    (ak/== value 5) "pulse"
    (ak/== value 6) "surge"
    :else "none"))

(a/defn- lane-text [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 0) "left"
    (ak/== value 2) "right"
    :else "same lane"))

(a/defn- status-text [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "hazard nearby"
    (ak/== value 2) "recovering"
    (ak/== value 3) "shield active"
    :else "clear"))

(a/defn- pit-state-text [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "called"
    (ak/== value 2) "servicing"
    (ak/== value 3) "exiting"
    (ak/== value 4) "retired"
    :else "out"))

(a/defn- tire-state-text [:slice-const :u8]
  [[percent :u8]]
  (if (<= percent 46) "worn" "usable"))

(a/defn- damage-state-text [:slice-const :u8]
  [[percent :u8]]
  (if (>= percent 60) "repair" "sound"))

(a/defn observation-prompt PromptBuffer
  "Describe one racer's bounded observation in ordinary compact English."
  [[request InferenceRequest]]
  (let [^:var bytes (std-mem/zeroes (a/type [:array 160 :u8]))
        progress-percent
        (ak/as (ak/intFromFloat
                (ak/min 99.0
                        (* (ak/max 0.0 (a/field request progress)) 100.0)))
               :u8)
        speed-percent
        (ak/as (ak/intFromFloat
                (ak/min 99.0
                        (* (ak/max 0.0 (a/field request speed)) 100.0)))
               :u8)
        rendered
        (catch
         (std-mem/print
          (ak/& bytes)
          "Driver {d}, {s}. Rank {d}/20; lap {d}; progress {d}%; speed {d}. Item {s}. Rival {d}: gap {d}, {s}. Track {s}. {s}."
          [(a/field request racer)
           (persona-text (a/field request persona))
           (a/field request rank)
           (a/field request lap)
           progress-percent
           speed-percent
           (item-text (a/field request item))
           (a/field request target)
           (a/field request target_distance)
           (lane-text (a/field request target_lane))
           (status-text (a/field request tactical_status))
           (if (a/field request urgent) "Urgent" "Routine")])
         (a/slice bytes 0 0))]
    (PromptBuffer
     {:byte_count (ak/intCast (a/field rendered len))
      :bytes bytes})))

(a/defn team-prompt PromptBuffer
  "Describe both team drivers and the shared pit box in ordinary English."
  [[request InferenceRequest]]
  (let [^:var bytes (std-mem/zeroes (a/type [:array 160 :u8]))
        rendered
        (catch
         (std-mem/print
         (ak/& bytes)
          "Team {d}. A{d}: rank {d}/20, tire {d}% {s}, damage {d}% {s}, {s}. B{d}: rank {d}/20, tire {d}% {s}, damage {d}% {s}, {s}. Box {s}."
          [(a/field request team)
           (a/field request driver_a)
           (a/field request rank_a)
           (a/field request tire_a)
           (tire-state-text (a/field request tire_a))
           (a/field request damage_a)
           (damage-state-text (a/field request damage_a))
           (pit-state-text (a/field request pit_a))
           (a/field request driver_b)
           (a/field request rank_b)
           (a/field request tire_b)
           (tire-state-text (a/field request tire_b))
           (a/field request damage_b)
           (damage-state-text (a/field request damage_b))
           (pit-state-text (a/field request pit_b))
           (if (a/field request box_occupied) "occupied" "free")])
         (a/slice bytes 0 0))]
    (PromptBuffer
     {:byte_count (ak/intCast (a/field rendered len))
      :bytes bytes})))

(a/defn request-prompt PromptBuffer
  "Select the semantic prompt contract for this independent AI actor."
  [[request InferenceRequest]]
  (if (ak/== (a/field request actor_kind) actor-kind-team)
    (team-prompt request)
    (observation-prompt request)))

(a/defn action-target-speed :f32
  "Translate one constrained action code into the racer's desired speed. This
  deliberately small hot unit is safe to tune while the native worker runs."
  [[action-code :u8]]
  (+ 0.076
     (* (ak/as (ak/floatFromInt (/ action-code 3)) :f32)
        0.008)))

(a/defn set-sampling-temperature! :f32
  "Tune constrained action diversity live. Values remain inside a stable,
  finite range and affect only future requests."
  [[temperature :f32]]
  (do
    (ak/= sampling-temperature (ak/max 0.25 (ak/min 8.0 temperature)))
    sampling-temperature))

(a/defn sample-action SampledAction
  "Temperature-sample only this actor's legal logits. Each actor owns one
  deterministic native RNG stream; no sampled value can name another tool."
  [[report inference/ForwardReport]
   [racer :usize]]
  (let [old-state (a/index sampler-states racer)
        next-state
        (ak/+% (ak/*% old-state 6364136223846793005)
               1442695040888963407)
        random-unit
        (/ (ak/as (ak/floatFromInt (ak/>> next-state 40))
                  :f32)
           16777216.0)
        ^:var maximum (ak/f32 (a/index (a/field report candidate_logits) 0))
        ^:var weights (ak/as (std-mem/zeroes (a/type [:array 8 :f32])) [:array 8 :f32])
        ^:var total (ak/f32 0.0)
        ^:var cumulative (ak/f32 0.0)
        candidate-count (ak/as (a/field report candidate_count) :usize)
        ^:var chosen (ak/u8 (ak/intCast (if (> candidate-count 0)
                            (- candidate-count 1)
                            0)))
        ^:var found (ak/bool false)]
    (dotimes [index candidate-count]
      (ak/= maximum
            (ak/max maximum
                    (a/index (a/field report candidate_logits) index))))
    (dotimes [index candidate-count]
      (let [weight
            (std-math/exp
             (/ (- (a/index (a/field report candidate_logits) index)
                   maximum)
                sampling-temperature))]
        (ak/= (a/index weights index) weight)
        (ak/= total (+ total weight))))
    (let [threshold (* random-unit total)]
      (dotimes [index candidate-count]
        (when (ak/! found)
          (ak/= cumulative (+ cumulative (a/index weights index)))
          (when (>= cumulative threshold)
            (ak/= chosen (ak/intCast index))
            (ak/= found true)))))
    (ak/= (a/index sampler-states racer) next-state)
    (SampledAction {:code chosen :state next-state})))

(a/defn interpret-action InferenceResult
  "Map a constrained driver or team token to its native validated action."
  [[request InferenceRequest]
   [report inference/ForwardReport]
   [prompt PromptBuffer]
   [tokens inference/TokenizationReport]
   [queue-us :u64]
  [inference-us :u64]]
  (let [team-actor (ak/== (a/field request actor_kind) actor-kind-team)
        candidate-count (ak/u8 (if team-actor 3 8))
        valid (and (a/field report valid)
                   (ak/== (a/field report candidate_count) candidate-count)
                   (>= (a/field report best_token) 32)
                   (< (a/field report best_token) (+ 32 candidate-count)))
        sampled
        (if valid
          (if team-actor
            ;; Pit-wall calls must match the verified three-action head exactly;
            ;; stochasticity here can call the healthy teammate by accident.
            (SampledAction
             {:code (ak/intCast (- (a/field report best_token) 32))
              :state (a/index sampler-states
                               (ak/intCast (a/field request racer)))})
            (sample-action report (ak/intCast (a/field request racer))))
          (SampledAction {:code 0 :state 0}))
        action-code (a/field sampled code)
        lane-code (if team-actor 1 (mod action-code 3))
        lane-target (ak/f32 (cond
                      (ak/== lane-code 0) -0.075
                      (ak/== lane-code 1) 0.0
                      :else 0.075))
        target-speed (if team-actor 0.0 (action-target-speed action-code))
        target (a/field request target)
        item-action (ak/u8 (if (and (ak/! team-actor) (>= action-code 4)) 1 0))
        ^:var input-tokens (std-mem/zeroes (a/type [:array 160 :u32]))
        ^:var output-tokens (std-mem/zeroes (a/type [:array 1 :u32]))
        ^:var response-bytes (std-mem/zeroes (a/type [:array 1 :u8]))]
    (dotimes [index (ak/min prompt-capacity (a/field tokens token_count))]
      (ak/= (a/index input-tokens index)
            (a/index (a/field tokens tokens) index)))
    (ak/= (a/index output-tokens 0) (+ 32 action-code))
    (ak/= (a/index response-bytes 0) (+ 65 action-code))
    (InferenceResult
     {:valid true
      :accepted valid
      :actor_kind (a/field request actor_kind)
      :team (a/field request team)
      :racer (a/field request racer)
      :rank (a/field request rank)
      :lap (a/field request lap)
      :item (a/field request item)
      :urgent (a/field request urgent)
      :observation_schema (a/field request observation_schema)
      :action_schema (a/field request action_schema)
      :action_code action-code
      :item_action item-action
      :target target
      :sampler_state (a/field sampled state)
      :revision (a/field request revision)
      :race_epoch (a/field request race_epoch)
      :simulation_tick (a/field request simulation_tick)
      :queue_us queue-us
      :prefill_us inference-us
      :decode_us 0
      :total_us (+ queue-us inference-us)
      :tokens_per_second
      (if (> inference-us 0)
        (/ (* (ak/as (ak/floatFromInt
                     (a/field tokens token_count))
                     :f32)
              1000000.0)
           (ak/as (ak/floatFromInt inference-us) :f32))
        0.0)
      :progress (a/field request progress)
      :speed (a/field request speed)
      :lane_target lane-target
      :target_speed target-speed
      :prompt_byte_count (a/field prompt byte_count)
      :input_token_count (a/field tokens token_count)
      :output_token_count 1
      :best_token (+ 32 action-code)
      :prompt_bytes (a/field prompt bytes)
      :input_tokens input-tokens
      :output_tokens output-tokens
      :response_bytes response-bytes})))

(a/defn process-request! :void
  "Run one complete prompt through the native model on the worker thread."
  [[request InferenceRequest]]
  (let [prompt (request-prompt request)
        prompt-length (ak/as (a/field prompt byte_count) :usize)
        tokenized
        (inference/tokenize-compact-ascii
         (ak/& (a/index (a/field prompt bytes) 0)) prompt-length)
        started (monotonic-seconds)
        queue-us
        (ak/as (ak/intFromFloat
                (* (ak/max 0.0 (- started (a/field request enqueue_seconds)))
                   1000000.0))
               :u64)
        report
        (inference/forward-compact-prompt!
         (ak/as (a/field request racer) :usize)
         (ak/& (a/index (a/field prompt bytes) 0)) prompt-length true)
        finished (monotonic-seconds)
        inference-us
        (ak/as (ak/intFromFloat
                (* (ak/max 0.0 (- finished started)) 1000000.0))
               :u64)
        result
        (interpret-action request report prompt tokenized queue-us inference-us)
        racer (ak/as (a/field request racer) :usize)]
    (ak/= (a/index results racer) result)
    (ak/atomicStore :u64 (ak/& (a/index result-revisions racer))
                    (a/field request revision) :.release)
    (ak/= :_ (ak/atomicRmw :u64 (ak/& result-count) :.Add 1 :.monotonic))
    (ak/= :_ (ak/atomicRmw :u64 (ak/& (a/index result-counts racer))
                          :.Add 1 :.monotonic))))

(a/defn- worker-loop! :void
  "Long-lived shell for one AI actor. Mutable model state and mailboxes are
  actor-disjoint; immutable weights remain shared across all actor threads."
  [[racer :usize]]
  (ak/while (ak/!= (ak/atomicLoad :u8 (ak/& worker-running) :.acquire) 0)
    (let [language-work (process-language-request! racer)
          revision
          (ak/atomicLoad :u64 (ak/& (a/index request-revisions racer))
                         :.acquire)
          found (and (ak/! language-work) (> revision (a/index consumed-revisions racer)))]
      (when found
        (let [request (a/index requests racer)]
          (ak/= (a/index consumed-revisions racer) revision)
          (when (and (a/field request valid)
                     (ak/== (a/field request revision) revision))
            (process-request! request))))
      (when (and (ak/! found) (ak/! language-work))
        (ak/= :_ (ak/atomicRmw :u64 (ak/& idle-wait-count)
                              :.Add 1 :.monotonic))
        (idle-wait!)))))

(a/defn start! :bool
  "Allocate one sequence state per actor and one fixed native worker per actor."
  []
  (if (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    true
    (if (ak/! (inference/initialize-sequences!))
      false
      (do
        (ak/= language-mailbox-states (std-mem/zeroes (a/type [:array actor-count :u8])))
        (ak/= language-requests (std-mem/zeroes (a/type [:array actor-count LanguageRequest])))
        (ak/= language-results (std-mem/zeroes (a/type [:array actor-count LanguageResult])))
        (ak/= requests (std-mem/zeroes (a/type [:array actor-count InferenceRequest])))
        (ak/= results (std-mem/zeroes (a/type [:array actor-count InferenceResult])))
        (ak/= request-revisions
              (std-mem/zeroes (a/type [:array actor-count :u64])))
        (ak/= consumed-revisions
              (std-mem/zeroes (a/type [:array actor-count :u64])))
        (ak/= result-revisions
              (std-mem/zeroes (a/type [:array actor-count :u64])))
        (ak/= worker-threads
              (std-mem/zeroes
               (a/type [:array actor-count [:optional aguafria.std/Thread]])))
        (ak/= worker-thread-count 0)
        (ak/= request-count 0)
        (ak/= result-count 0)
        (ak/= request-counts
              (std-mem/zeroes (a/type [:array actor-count :u64])))
        (ak/= result-counts
              (std-mem/zeroes (a/type [:array actor-count :u64])))
        (ak/= idle-wait-count 0)
        (dotimes [actor actor-count]
          (ak/= (a/index sampler-states actor)
                (+ 101 (* (ak/as (ak/intCast actor) :u64) 103))))
        (ak/atomicStore :u8 (ak/& worker-running) 1 :.release)
        (let [^:var all-started (ak/bool true)]
          (dotimes [racer actor-count]
            (when all-started
              (let [thread
                    (catch
                     (std-thread/spawn {:stack_size 1048576}
                                       worker-loop! [racer])
                     ak/null)]
                (if (ak/== thread ak/null)
                  (ak/= all-started false)
                  (do
                    (ak/= (a/index worker-threads racer) thread)
                    (ak/= worker-thread-count
                          (+ worker-thread-count 1)))))))
          (if all-started
            (do
              (ak/atomicStore :u8 (ak/& worker-started) 1 :.release)
              true)
            (do
              (ak/atomicStore :u8 (ak/& worker-running) 0 :.release)
              (dotimes [racer actor-count]
                (when (ak/!= (a/index worker-threads racer) ak/null)
                  (std-thread/join
                   (a/unwrap (a/index worker-threads racer)))
                  (ak/= (a/index worker-threads racer) ak/null)))
              (ak/= worker-thread-count 0)
              (inference/free-sequences!)
              false)))))))

(a/defn submit! :bool
  "Publish one immutable observation. A racer has only one in-flight request."
  [[request InferenceRequest]]
  (let [racer (ak/as (a/field request racer) :usize)
        ^:var published request
        requested
        (if (< racer actor-count)
          (ak/atomicLoad :u64 (ak/& (a/index request-revisions racer))
                         :.acquire)
          0)
        completed
        (if (< racer actor-count)
          (ak/atomicLoad :u64 (ak/& (a/index result-revisions racer))
                         :.acquire)
          0)]
    (if (or (ak/== (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
            (ak/! (a/field request valid))
            (>= racer actor-count)
            (> requested completed)
            (ak/!= (a/field request observation_schema)
                   protocol/observation-schema-version)
            (ak/!= (a/field request action_schema)
                   protocol/action-schema-version)
            (ak/== (a/field request revision) 0))
      false
      (do
        (ak/= (a/field published enqueue_seconds) (monotonic-seconds))
        (ak/= (a/index requests racer) published)
        (ak/atomicStore :u64 (ak/& (a/index request-revisions racer))
                        (a/field published revision) :.release)
        (ak/= :_ (ak/atomicRmw :u64 (ak/& request-count)
                              :.Add 1 :.monotonic))
        (ak/= :_ (ak/atomicRmw :u64 (ak/& (a/index request-counts racer))
                              :.Add 1 :.monotonic))
        true))))

(a/defn result-for InferenceResult
  "Read the newest fully published result for one racer."
  [[racer :usize]
   [after-revision :u64]]
  (if (>= racer actor-count)
    (empty-result)
    (let [revision
          (ak/atomicLoad :u64 (ak/& (a/index result-revisions racer))
                         :.acquire)]
      (if (> revision after-revision)
        (a/index results racer)
        (empty-result)))))

(a/defn summary WorkerSummary
  []
  (let [^:var pending (ak/u8 0)]
    (dotimes [racer actor-count]
      (when (or (let [state (language-mailbox-state racer)]
                  (or (ak/== state 1) (ak/== state 2)))
                (> (ak/atomicLoad :u64
                              (ak/& (a/index request-revisions racer))
                              :.acquire)
               (ak/atomicLoad :u64
                              (ak/& (a/index result-revisions racer))
                              :.acquire)))
        (ak/= pending (+ pending 1))))
    (WorkerSummary
     {:running (ak/!= (ak/atomicLoad :u8 (ak/& worker-running) :.acquire) 0)
      :started (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
      :threads worker-thread-count
      :requests (ak/atomicLoad :u64 (ak/& request-count) :.acquire)
      :results (ak/atomicLoad :u64 (ak/& result-count) :.acquire)
      :idle_waits (ak/atomicLoad :u64 (ak/& idle-wait-count) :.acquire)
      :pending pending
      :requests_by_actor request-counts
      :results_by_actor result-counts
      :state_bytes inference/sequence-total-bytes})))

(a/defn stop! :void
  "Join the worker before model memory or native libraries are released."
  []
  (when (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    (ak/atomicStore :u8 (ak/& worker-running) 0 :.release)
    (dotimes [racer actor-count]
      (when (ak/!= (a/index worker-threads racer) ak/null)
        (std-thread/join (a/unwrap (a/index worker-threads racer)))
        (ak/= (a/index worker-threads racer) ak/null)))
    (ak/= worker-thread-count 0)
    (ak/atomicStore :u8 (ak/& worker-started) 0 :.release)))
