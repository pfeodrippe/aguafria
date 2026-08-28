(ns racing-game.worker
  "Fixed native inference workers with one bounded mailbox per AI actor."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.Thread :as std-thread]
            [aguafria.std.c :as std-c]
            [aguafria.std.fmt :as std-fmt]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [racing-game.inference :as inference]
            [racing-game.protocol :as protocol]))

(az/defconst actor-count :usize 12)

(az/defconst racer-count :usize 8)

(az/defconst team-count :usize 4)

(az/defconst actor-kind-driver :u8 0)

(az/defconst actor-kind-team :u8 1)

(az/defconst prompt-capacity :usize 160)

(az/defvar sampling-temperature :f32 0.35)

(az/defstruct SampledAction
  "One reproducible constrained draw and the exact post-draw RNG state."
  {:layout :extern}
  [[:code :u8]
   [:state :u64]])

(az/defstruct PromptBuffer
  "One bounded human-readable prompt owned by the request worker stack."
  {:layout :extern}
  [[:byte_count :u16]
   [:bytes [:array 160 :u8]]])

(az/defstruct InferenceRequest
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

(az/defstruct InferenceResult
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

(az/defstruct WorkerSummary
  "Clojure-readable state for the real native worker and its mailboxes."
  {:layout :extern}
  [[:running :bool]
   [:started :bool]
   [:threads :u8]
   [:requests :u64]
   [:results :u64]
   [:idle_waits :u64]
   [:pending :u8]
   [:requests_by_actor [:array 12 :u64]]
   [:results_by_actor [:array 12 :u64]]
   [:state_bytes :usize]])

(az/defvar requests [:array 12 InferenceRequest]
  (std-mem/zeroes (az/type [:array 12 InferenceRequest])))

(az/defvar request-revisions [:array 12 :u64]
  (std-mem/zeroes (az/type [:array 12 :u64])))

(az/defvar consumed-revisions [:array 12 :u64]
  (std-mem/zeroes (az/type [:array 12 :u64])))

(az/defvar results [:array 12 InferenceResult]
  (std-mem/zeroes (az/type [:array 12 InferenceResult])))

(az/defvar result-revisions [:array 12 :u64]
  (std-mem/zeroes (az/type [:array 12 :u64])))

(az/defvar worker-running :u8 0)

(az/defvar worker-started :u8 0)

(az/defvar worker-threads [:array 12 [:optional aguafria.std/Thread]]
  (std-mem/zeroes
   (az/type [:array 12 [:optional aguafria.std/Thread]])))

(az/defvar worker-thread-count :u8 0)

(az/defvar request-count :u64 0)

(az/defvar result-count :u64 0)

(az/defvar request-counts [:array 12 :u64]
  (std-mem/zeroes (az/type [:array 12 :u64])))

(az/defvar result-counts [:array 12 :u64]
  (std-mem/zeroes (az/type [:array 12 :u64])))

(az/defvar sampler-states [:array 12 :u64]
  (az/array-init [:array 12 :u64]
                 [101 203 307 409 503 607 709 811 907 1009 1103 1201]))

(az/defvar idle-wait-count :u64 0)

(az/defn empty-result
  {:export false :public false :implicit-return true}
  :-
  InferenceResult
  []
  (std-mem/zeroes (az/type InferenceResult)))

(az/defn monotonic-seconds
  "Read the operating system monotonic clock without depending on a windowing
  event loop. This is safe from desktop, headless nREPL, and worker threads."
  {:export false :public false :implicit-return true}
  :-
  :f64
  []
  (let [^:var timestamp (std-mem/zeroes (az/type std-c/timespec))
        result (std-c/clock_gettime :.MONOTONIC (ak/& timestamp))]
    (if (ak/== result 0)
      (+ (ak/as :f64 (ak/floatFromInt (az/field timestamp sec)))
         (/ (ak/as :f64 (ak/floatFromInt (az/field timestamp nsec)))
            1000000000.0))
      0.0)))

(az/defn idle-wait!
  "Yield the worker core for half a millisecond without coupling it to an I/O
  runtime. Mailbox latency remains negligible beside one native LLM pass."
  {:export false :public false}
  :-
  :void
  []
  (let [^{:var true :zig/type std-c/timespec}
        duration (std-mem/zeroes (az/type std-c/timespec))]
    (set! (az/field duration nsec) 500000)
    (set! _ (std-c/nanosleep (ak/& duration) null))))

(az/defn persona-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 0) "cautious"
    (ak/== value 1) "balanced"
    :else "bold"))

(az/defn item-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "bolt"
    (ak/== value 2) "trap"
    (ak/== value 3) "boost"
    (ak/== value 4) "shield"
    (ak/== value 5) "pulse"
    (ak/== value 6) "surge"
    :else "none"))

(az/defn lane-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 0) "left"
    (ak/== value 2) "right"
    :else "same lane"))

(az/defn status-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "hazard nearby"
    (ak/== value 2) "recovering"
    (ak/== value 3) "shield active"
    :else "clear"))

(az/defn pit-state-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[value :u8]]
  (cond
    (ak/== value 1) "called"
    (ak/== value 2) "servicing"
    (ak/== value 3) "exiting"
    :else "out"))

(az/defn tire-state-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[percent :u8]]
  (if (<= percent 46) "worn" "usable"))

(az/defn damage-state-text
  {:export false :public false :implicit-return true}
  :-
  [:slice-const :u8]
  [[percent :u8]]
  (if (>= percent 60) "repair" "sound"))

(az/defn observation-prompt
  "Describe one racer's bounded observation in ordinary compact English."
  {:export false :implicit-return true}
  :-
  PromptBuffer
  [[request InferenceRequest]]
  (let [^:var bytes (std-mem/zeroes (az/type [:array 160 :u8]))
        progress-percent
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 99.0
                        (* (ak/max 0.0 (az/field request progress)) 100.0))))
        speed-percent
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 99.0
                        (* (ak/max 0.0 (az/field request speed)) 100.0))))
        rendered
        (catch
         (std-fmt/bufPrint
          (ak/& bytes)
          "Driver {d}, {s}. Rank {d}/8; lap {d}; progress {d}%; speed {d}. Item {s}. Rival {d}: gap {d}, {s}. Track {s}. {s}."
          [(az/field request racer)
           (persona-text (az/field request persona))
           (az/field request rank)
           (az/field request lap)
           progress-percent
           speed-percent
           (item-text (az/field request item))
           (az/field request target)
           (az/field request target_distance)
           (lane-text (az/field request target_lane))
           (status-text (az/field request tactical_status))
           (if (az/field request urgent) "Urgent" "Routine")])
         (az/slice bytes 0 0))]
    (PromptBuffer
     {:byte_count (ak/intCast (az/field rendered len))
      :bytes bytes})))

(az/defn team-prompt
  "Describe both team drivers and the shared pit box in ordinary English."
  {:export false :implicit-return true}
  :-
  PromptBuffer
  [[request InferenceRequest]]
  (let [^:var bytes (std-mem/zeroes (az/type [:array 160 :u8]))
        rendered
        (catch
         (std-fmt/bufPrint
         (ak/& bytes)
          "Team {d}. A{d}: rank {d}/8, tire {d}% {s}, damage {d}% {s}, {s}. B{d}: rank {d}/8, tire {d}% {s}, damage {d}% {s}, {s}. Box {s}."
          [(az/field request team)
           (az/field request driver_a)
           (az/field request rank_a)
           (az/field request tire_a)
           (tire-state-text (az/field request tire_a))
           (az/field request damage_a)
           (damage-state-text (az/field request damage_a))
           (pit-state-text (az/field request pit_a))
           (az/field request driver_b)
           (az/field request rank_b)
           (az/field request tire_b)
           (tire-state-text (az/field request tire_b))
           (az/field request damage_b)
           (damage-state-text (az/field request damage_b))
           (pit-state-text (az/field request pit_b))
           (if (az/field request box_occupied) "occupied" "free")])
         (az/slice bytes 0 0))]
    (PromptBuffer
     {:byte_count (ak/intCast (az/field rendered len))
      :bytes bytes})))

(az/defn request-prompt
  "Select the semantic prompt contract for this independent AI actor."
  {:export false :implicit-return true}
  :-
  PromptBuffer
  [[request InferenceRequest]]
  (if (ak/== (az/field request actor_kind) actor-kind-team)
    (team-prompt request)
    (observation-prompt request)))

(az/defn action-target-speed
  "Translate one constrained action code into the racer's desired speed. This
  deliberately small hot unit is safe to tune while the native worker runs."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[action-code :u8]]
  (+ 0.076
     (* (ak/as :f32 (ak/floatFromInt (/ action-code 3)))
        0.008)))

(az/defn set-sampling-temperature!
  "Tune constrained action diversity live. Values remain inside a stable,
  finite range and affect only future requests."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[temperature :f32]]
  (do
    (set! sampling-temperature (ak/max 0.25 (ak/min 8.0 temperature)))
    sampling-temperature))

(az/defn sample-action
  "Temperature-sample only this actor's legal logits. Each actor owns one
  deterministic native RNG stream; no sampled value can name another tool."
  {:export false :implicit-return true}
  :-
  SampledAction
  [[report inference/ForwardReport]
   [racer :usize]]
  (let [old-state (az/index sampler-states racer)
        next-state
        (ak/+% (ak/*% old-state 6364136223846793005)
               1442695040888963407)
        random-unit
        (/ (ak/as :f32
                  (ak/floatFromInt (ak/>> next-state 40)))
           16777216.0)
        ^{:var true :zig/type :f32}
        maximum (az/index (az/field report candidate_logits) 0)
        ^{:var true :zig/type [:array 8 :f32]}
        weights (std-mem/zeroes (az/type [:array 8 :f32]))
        ^{:var true :zig/type :f32} total 0.0
        ^{:var true :zig/type :f32} cumulative 0.0
        candidate-count (ak/as :usize (az/field report candidate_count))
        ^{:var true :zig/type :u8}
        chosen (ak/intCast (if (> candidate-count 0)
                            (- candidate-count 1)
                            0))
        ^{:var true :zig/type :bool} found false]
    (dotimes [index candidate-count]
      (set! maximum
            (ak/max maximum
                    (az/index (az/field report candidate_logits) index))))
    (dotimes [index candidate-count]
      (let [weight
            (std-math/exp
             (/ (- (az/index (az/field report candidate_logits) index)
                   maximum)
                sampling-temperature))]
        (set! (az/index weights index) weight)
        (set! total (+ total weight))))
    (let [threshold (* random-unit total)]
      (dotimes [index candidate-count]
        (when (ak/! found)
          (set! cumulative (+ cumulative (az/index weights index)))
          (when (>= cumulative threshold)
            (set! chosen (ak/intCast index))
            (set! found true)))))
    (set! (az/index sampler-states racer) next-state)
    (SampledAction {:code chosen :state next-state})))

(az/defn interpret-action
  "Map a constrained driver or team token to its native validated action."
  {:export false :implicit-return true}
  :-
  InferenceResult
  [[request InferenceRequest]
   [report inference/ForwardReport]
   [prompt PromptBuffer]
   [tokens inference/TokenizationReport]
   [queue-us :u64]
  [inference-us :u64]]
  (let [team-actor (ak/== (az/field request actor_kind) actor-kind-team)
        ^{:zig/type :u8}
        candidate-count (if team-actor 3 8)
        valid (and (az/field report valid)
                   (ak/== (az/field report candidate_count) candidate-count)
                   (>= (az/field report best_token) 32)
                   (< (az/field report best_token) (+ 32 candidate-count)))
        sampled
        (if valid
          (if team-actor
            ;; Pit-wall calls must match the verified three-action head exactly;
            ;; stochasticity here can call the healthy teammate by accident.
            (SampledAction
             {:code (ak/intCast (- (az/field report best_token) 32))
              :state (az/index sampler-states
                               (ak/intCast (az/field request racer)))})
            (sample-action report (ak/intCast (az/field request racer))))
          (SampledAction {:code 0 :state 0}))
        action-code (az/field sampled code)
        lane-code (if team-actor 1 (mod action-code 3))
        ^{:zig/type :f32}
        lane-target (cond
                      (ak/== lane-code 0) -0.075
                      (ak/== lane-code 1) 0.0
                      :else 0.075)
        target-speed (if team-actor 0.0 (action-target-speed action-code))
        target (az/field request target)
        ^{:zig/type :u8}
        item-action (if (and (ak/! team-actor) (>= action-code 4)) 1 0)
        ^:var input-tokens (std-mem/zeroes (az/type [:array 160 :u32]))
        ^:var output-tokens (std-mem/zeroes (az/type [:array 1 :u32]))
        ^:var response-bytes (std-mem/zeroes (az/type [:array 1 :u8]))]
    (dotimes [index (ak/min prompt-capacity (az/field tokens token_count))]
      (set! (az/index input-tokens index)
            (az/index (az/field tokens tokens) index)))
    (set! (az/index output-tokens 0) (+ 32 action-code))
    (set! (az/index response-bytes 0) (+ 65 action-code))
    (InferenceResult
     {:valid true
      :accepted valid
      :actor_kind (az/field request actor_kind)
      :team (az/field request team)
      :racer (az/field request racer)
      :rank (az/field request rank)
      :lap (az/field request lap)
      :item (az/field request item)
      :urgent (az/field request urgent)
      :observation_schema (az/field request observation_schema)
      :action_schema (az/field request action_schema)
      :action_code action-code
      :item_action item-action
      :target target
      :sampler_state (az/field sampled state)
      :revision (az/field request revision)
      :race_epoch (az/field request race_epoch)
      :simulation_tick (az/field request simulation_tick)
      :queue_us queue-us
      :prefill_us inference-us
      :decode_us 0
      :total_us (+ queue-us inference-us)
      :tokens_per_second
      (if (> inference-us 0)
        (/ (* (ak/as :f32
                     (ak/floatFromInt
                     (az/field tokens token_count)))
              1000000.0)
           (ak/as :f32 (ak/floatFromInt inference-us)))
        0.0)
      :progress (az/field request progress)
      :speed (az/field request speed)
      :lane_target lane-target
      :target_speed target-speed
      :prompt_byte_count (az/field prompt byte_count)
      :input_token_count (az/field tokens token_count)
      :output_token_count 1
      :best_token (+ 32 action-code)
      :prompt_bytes (az/field prompt bytes)
      :input_tokens input-tokens
      :output_tokens output-tokens
      :response_bytes response-bytes})))

(az/defn process-request!
  "Run one complete prompt through the native model on the worker thread."
  {:export false}
  :-
  :void
  [[request InferenceRequest]]
  (let [prompt (request-prompt request)
        prompt-length (ak/as :usize (az/field prompt byte_count))
        tokenized
        (inference/tokenize-compact-ascii
         (ak/& (az/index (az/field prompt bytes) 0)) prompt-length)
        started (monotonic-seconds)
        queue-us
        (ak/as :u64
               (ak/intFromFloat
                (* (ak/max 0.0 (- started (az/field request enqueue_seconds)))
                   1000000.0)))
        report
        (inference/forward-compact-prompt!
         (ak/as :usize (az/field request racer))
         (ak/& (az/index (az/field prompt bytes) 0)) prompt-length true)
        finished (monotonic-seconds)
        inference-us
        (ak/as :u64
               (ak/intFromFloat
                (* (ak/max 0.0 (- finished started)) 1000000.0)))
        result
        (interpret-action request report prompt tokenized queue-us inference-us)
        racer (ak/as :usize (az/field request racer))]
    (set! (az/index results racer) result)
    (ak/atomicStore :u64 (ak/& (az/index result-revisions racer))
                    (az/field request revision) :.release)
    (set! _ (ak/atomicRmw :u64 (ak/& result-count) :.Add 1 :.monotonic))
    (set! _ (ak/atomicRmw :u64 (ak/& (az/index result-counts racer))
                          :.Add 1 :.monotonic))))

(az/defn worker-loop!
  "Long-lived shell for one AI actor. Mutable model state and mailboxes are
  actor-disjoint; immutable weights remain shared across all twelve threads."
  {:export false :public false}
  :-
  :void
  [[racer :usize]]
  (ak/while (ak/!= (ak/atomicLoad :u8 (ak/& worker-running) :.acquire) 0)
    (let [revision
          (ak/atomicLoad :u64 (ak/& (az/index request-revisions racer))
                         :.acquire)
          found (> revision (az/index consumed-revisions racer))]
      (when found
        (let [request (az/index requests racer)]
          (set! (az/index consumed-revisions racer) revision)
          (when (and (az/field request valid)
                     (ak/== (az/field request revision) revision))
            (process-request! request))))
      (when (ak/! found)
        (set! _ (ak/atomicRmw :u64 (ak/& idle-wait-count)
                              :.Add 1 :.monotonic))
        (idle-wait!)))))

(az/defn start!
  "Allocate twelve sequence states and one fixed native worker per actor."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (if (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    true
    (if (ak/! (inference/initialize-sequences!))
      false
      (do
        (set! requests (std-mem/zeroes (az/type [:array 12 InferenceRequest])))
        (set! results (std-mem/zeroes (az/type [:array 12 InferenceResult])))
        (set! request-revisions
              (std-mem/zeroes (az/type [:array 12 :u64])))
        (set! consumed-revisions
              (std-mem/zeroes (az/type [:array 12 :u64])))
        (set! result-revisions
              (std-mem/zeroes (az/type [:array 12 :u64])))
        (set! worker-threads
              (std-mem/zeroes
               (az/type [:array 12 [:optional aguafria.std/Thread]])))
        (set! worker-thread-count 0)
        (set! request-count 0)
        (set! result-count 0)
        (set! request-counts
              (std-mem/zeroes (az/type [:array 12 :u64])))
        (set! result-counts
              (std-mem/zeroes (az/type [:array 12 :u64])))
        (set! idle-wait-count 0)
        (set! sampler-states
              (az/array-init
               [:array 12 :u64]
               [101 203 307 409 503 607 709 811 907 1009 1103 1201]))
        (ak/atomicStore :u8 (ak/& worker-running) 1 :.release)
        (let [^{:var true :zig/type :bool} all-started true]
          (dotimes [racer actor-count]
            (when all-started
              (let [thread
                    (catch
                     (std-thread/spawn {:stack_size 1048576}
                                       worker-loop! [racer])
                     null)]
                (if (ak/== thread null)
                  (set! all-started false)
                  (do
                    (set! (az/index worker-threads racer) thread)
                    (set! worker-thread-count
                          (+ worker-thread-count 1)))))))
          (if all-started
            (do
              (ak/atomicStore :u8 (ak/& worker-started) 1 :.release)
              true)
            (do
              (ak/atomicStore :u8 (ak/& worker-running) 0 :.release)
              (dotimes [racer actor-count]
                (when (ak/!= (az/index worker-threads racer) null)
                  (std-thread/join
                   (az/unwrap (az/index worker-threads racer)))
                  (set! (az/index worker-threads racer) null)))
              (set! worker-thread-count 0)
              (inference/free-sequences!)
              false)))))))

(az/defn submit!
  "Publish one immutable observation. A racer has only one in-flight request."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[request InferenceRequest]]
  (let [racer (ak/as :usize (az/field request racer))
        ^:var published request
        requested
        (if (< racer actor-count)
          (ak/atomicLoad :u64 (ak/& (az/index request-revisions racer))
                         :.acquire)
          0)
        completed
        (if (< racer actor-count)
          (ak/atomicLoad :u64 (ak/& (az/index result-revisions racer))
                         :.acquire)
          0)]
    (if (or (ak/== (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
            (ak/! (az/field request valid))
            (>= racer actor-count)
            (> requested completed)
            (ak/!= (az/field request observation_schema)
                   protocol/observation-schema-version)
            (ak/!= (az/field request action_schema)
                   protocol/action-schema-version)
            (ak/== (az/field request revision) 0))
      false
      (do
        (set! (az/field published enqueue_seconds) (monotonic-seconds))
        (set! (az/index requests racer) published)
        (ak/atomicStore :u64 (ak/& (az/index request-revisions racer))
                        (az/field published revision) :.release)
        (set! _ (ak/atomicRmw :u64 (ak/& request-count)
                              :.Add 1 :.monotonic))
        (set! _ (ak/atomicRmw :u64 (ak/& (az/index request-counts racer))
                              :.Add 1 :.monotonic))
        true))))

(az/defn result-for
  "Read the newest fully published result for one racer."
  {:attrs #{:public :implicit-return}}
  :-
  InferenceResult
  [[racer :usize]
   [after-revision :u64]]
  (if (>= racer actor-count)
    (empty-result)
    (let [revision
          (ak/atomicLoad :u64 (ak/& (az/index result-revisions racer))
                         :.acquire)]
      (if (> revision after-revision)
        (az/index results racer)
        (empty-result)))))

(az/defn summary
  {:attrs #{:public :implicit-return}}
  :-
  WorkerSummary
  []
  (let [^{:var true :zig/type :u8} pending 0]
    (dotimes [racer actor-count]
      (when (> (ak/atomicLoad :u64
                              (ak/& (az/index request-revisions racer))
                              :.acquire)
               (ak/atomicLoad :u64
                              (ak/& (az/index result-revisions racer))
                              :.acquire))
        (set! pending (+ pending 1))))
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

(az/defn stop!
  "Join the worker before model memory or native libraries are released."
  {:attrs #{:public}}
  :-
  :void
  []
  (when (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    (ak/atomicStore :u8 (ak/& worker-running) 0 :.release)
    (dotimes [racer actor-count]
      (when (ak/!= (az/index worker-threads racer) null)
        (std-thread/join (az/unwrap (az/index worker-threads racer)))
        (set! (az/index worker-threads racer) null)))
    (set! worker-thread-count 0)
    (ak/atomicStore :u8 (ak/& worker-started) 0 :.release)))
