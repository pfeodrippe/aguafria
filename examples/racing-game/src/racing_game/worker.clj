(ns racing-game.worker
  "Fixed native inference workers with one bounded mailbox per racer."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.Thread :as std-thread]
            [aguafria.std.c :as std-c]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [racing-game.inference :as inference]
            [racing-game.protocol :as protocol]))

(az/defconst racer-count :usize 8)

(az/defconst prompt-length :usize 8)

(az/defvar sampling-temperature :f32 0.35)

(az/defstruct SampledAction
  "One reproducible constrained draw and the exact post-draw RNG state."
  {:layout :extern}
  [[:code :u8]
   [:state :u64]])

(az/defstruct InferenceRequest
  "Immutable observation published by the 120 Hz simulation."
  {:layout :extern}
  [[:valid :bool]
   [:racer :u8]
   [:rank :u8]
   [:lap :u16]
   [:item :u8]
   [:target :u8]
   [:persona :u8]
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
   [:prompt_bytes [:array 32 :u8]]
   [:input_tokens [:array 32 :u32]]
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
   [:state_bytes :usize]])

(az/defvar requests [:array 8 InferenceRequest]
  (std-mem/zeroes (az/type [:array 8 InferenceRequest])))

(az/defvar request-revisions [:array 8 :u64]
  (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))

(az/defvar consumed-revisions [:array 8 :u64]
  (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))

(az/defvar results [:array 8 InferenceResult]
  (std-mem/zeroes (az/type [:array 8 InferenceResult])))

(az/defvar result-revisions [:array 8 :u64]
  (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))

(az/defvar worker-running :u8 0)

(az/defvar worker-started :u8 0)

(az/defvar worker-threads [:array 8 [:optional aguafria.std/Thread]]
  (std-mem/zeroes
   (az/type [:array 8 [:optional aguafria.std/Thread]])))

(az/defvar worker-thread-count :u8 0)

(az/defvar request-count :u64 0)

(az/defvar result-count :u64 0)

(az/defvar sampler-states [:array 8 :u64]
  (az/array-init [:array 8 :u64]
                 [101 203 307 409 503 607 709 811]))

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

(az/defn category-byte
  "Encode one bounded categorical value as printable A-Z for the positional
  racing prompt schema."
  {:export false :public false :implicit-return true}
  :-
  :u8
  [[value :u8]]
  (+ (ak/as :u8 65) (ak/min value (ak/as :u8 25))))

(az/defn observation-prompt
  "Encode prompt schema R2 as eight positional bytes: schema,
  target/persona, rank, lap, item, progress bin, speed bin, and urgency. The
  second byte is `target * 3 + persona`; A means zero, B means one, and so on.
  The bounded representation is allocation-free and fine-tunable."
  {:export false :implicit-return true}
  :-
  [:array 32 :u8]
  [[request InferenceRequest]]
  (let [^:var bytes (std-mem/zeroes (az/type [:array 32 :u8]))
        progress-bin
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 9.0 (* (ak/max 0.0 (az/field request progress)) 10.0))))
        speed-bin
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 9.0 (* (ak/max 0.0 (az/field request speed)) 100.0))))]
    ;; S is the stable wire identifier for observation schema R2.
    (set! (az/index bytes 0) (+ 81 protocol/observation-schema-version))
    (set! (az/index bytes 1)
          (category-byte
           (+ (* (az/field request target) 3)
              (ak/min (az/field request persona) (ak/as :u8 2)))))
    (set! (az/index bytes 2)
          (category-byte
           (if (> (az/field request rank) 0)
             (- (az/field request rank) 1)
             0)))
    (set! (az/index bytes 3)
          (category-byte (ak/intCast (az/field request lap))))
    (set! (az/index bytes 4)
          (category-byte (az/field request item)))
    (set! (az/index bytes 5) (category-byte progress-bin))
    (set! (az/index bytes 6) (category-byte speed-bin))
    (set! (az/index bytes 7)
          (category-byte (if (az/field request urgent) 1 0)))
    bytes))

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
  "Temperature-sample only the eight legal A-H logits. Each racer owns one
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
        ^{:var true :zig/type :u8} chosen 7
        ^{:var true :zig/type :bool} found false]
    (dotimes [index 8]
      (set! maximum
            (ak/max maximum
                    (az/index (az/field report candidate_logits) index))))
    (dotimes [index 8]
      (let [weight
            (std-math/exp
             (/ (- (az/index (az/field report candidate_logits) index)
                   maximum)
                sampling-temperature))]
        (set! (az/index weights index) weight)
        (set! total (+ total weight))))
    (let [threshold (* random-unit total)]
      (dotimes [index 8]
        (when (ak/! found)
          (set! cumulative (+ cumulative (az/index weights index)))
          (when (>= cumulative threshold)
            (set! chosen (ak/intCast index))
            (set! found true)))))
    (set! (az/index sampler-states racer) next-state)
    (SampledAction {:code chosen :state next-state})))

(az/defn interpret-action
  "Map the constrained A-H token to a complete legal racing intent."
  {:export false :implicit-return true}
  :-
  InferenceResult
  [[request InferenceRequest]
   [report inference/ForwardReport]
   [prompt [:array 32 :u8]]
   [tokens inference/TokenizationReport]
   [queue-us :u64]
   [inference-us :u64]]
  (let [valid (and (az/field report valid)
                   (>= (az/field report best_token) 32)
                   (< (az/field report best_token) 40))
        sampled
        (if valid
          (sample-action report (ak/intCast (az/field request racer)))
          (SampledAction {:code 0 :state 0}))
        action-code (az/field sampled code)
        lane-code (mod action-code 3)
        ^{:zig/type :f32}
        lane-target (cond
                      (ak/== lane-code 0) -0.075
                      (ak/== lane-code 1) 0.0
                      :else 0.075)
        target-speed (action-target-speed action-code)
        target (az/field request target)
        ^{:zig/type :u8}
        item-action (if (>= action-code 4) 1 0)
        ^:var input-tokens (std-mem/zeroes (az/type [:array 32 :u32]))
        ^:var output-tokens (std-mem/zeroes (az/type [:array 1 :u32]))
        ^:var response-bytes (std-mem/zeroes (az/type [:array 1 :u8]))]
    (dotimes [index (ak/min 32 (az/field tokens token_count))]
      (set! (az/index input-tokens index)
            (az/index (az/field tokens tokens) index)))
    (set! (az/index output-tokens 0) (+ 32 action-code))
    (set! (az/index response-bytes 0) (+ 65 action-code))
    (InferenceResult
     {:valid true
      :accepted valid
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
                     (ak/floatFromInt (az/field tokens token_count)))
              1000000.0)
           (ak/as :f32 (ak/floatFromInt inference-us)))
        0.0)
      :progress (az/field request progress)
      :speed (az/field request speed)
      :lane_target lane-target
      :target_speed target-speed
      :prompt_byte_count (ak/intCast prompt-length)
      :input_token_count (az/field tokens token_count)
      :output_token_count 1
      :best_token (+ 32 action-code)
      :prompt_bytes prompt
      :input_tokens input-tokens
      :output_tokens output-tokens
      :response_bytes response-bytes})))

(az/defn process-request!
  "Run one complete prompt through the native model on the worker thread."
  {:export false}
  :-
  :void
  [[request InferenceRequest]]
  (let [prompt (observation-prompt request)
        tokenized
        (inference/tokenize-compact-ascii (ak/& (az/index prompt 0)) prompt-length)
        started (monotonic-seconds)
        queue-us
        (ak/as :u64
               (ak/intFromFloat
                (* (ak/max 0.0 (- started (az/field request enqueue_seconds)))
                   1000000.0)))
        report
        (inference/forward-compact-prompt!
         (ak/as :usize (az/field request racer))
         (ak/& (az/index prompt 0)) prompt-length true)
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
    (set! _ (ak/atomicRmw :u64 (ak/& result-count) :.Add 1 :.monotonic))))

(az/defn worker-loop!
  "Long-lived shell for one racer. Mutable model state and mailboxes are
  racer-disjoint; immutable weights remain shared across all eight threads."
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
  "Allocate eight sequence states and one fixed native worker per racer."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (if (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    true
    (if (ak/! (inference/initialize-sequences!))
      false
      (do
        (set! requests (std-mem/zeroes (az/type [:array 8 InferenceRequest])))
        (set! results (std-mem/zeroes (az/type [:array 8 InferenceResult])))
        (set! request-revisions
              (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))
        (set! consumed-revisions
              (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))
        (set! result-revisions
              (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))
        (set! worker-threads
              (std-mem/zeroes
               (az/type [:array 8 [:optional aguafria.std/Thread]])))
        (set! worker-thread-count 0)
        (set! request-count 0)
        (set! result-count 0)
        (set! idle-wait-count 0)
        (set! sampler-states
              (az/array-init [:array 8 :u64]
                             [101 203 307 409 503 607 709 811]))
        (ak/atomicStore :u8 (ak/& worker-running) 1 :.release)
        (let [^{:var true :zig/type :bool} all-started true]
          (dotimes [racer racer-count]
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
              (dotimes [racer racer-count]
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
        (if (< racer racer-count)
          (ak/atomicLoad :u64 (ak/& (az/index request-revisions racer))
                         :.acquire)
          0)
        completed
        (if (< racer racer-count)
          (ak/atomicLoad :u64 (ak/& (az/index result-revisions racer))
                         :.acquire)
          0)]
    (if (or (ak/== (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
            (ak/! (az/field request valid))
            (>= racer racer-count)
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
        true))))

(az/defn result-for
  "Read the newest fully published result for one racer."
  {:attrs #{:public :implicit-return}}
  :-
  InferenceResult
  [[racer :usize]
   [after-revision :u64]]
  (if (>= racer racer-count)
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
    (dotimes [racer racer-count]
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
      :state_bytes inference/sequence-total-bytes})))

(az/defn stop!
  "Join the worker before model memory or native libraries are released."
  {:attrs #{:public}}
  :-
  :void
  []
  (when (ak/!= (ak/atomicLoad :u8 (ak/& worker-started) :.acquire) 0)
    (ak/atomicStore :u8 (ak/& worker-running) 0 :.release)
    (dotimes [racer racer-count]
      (when (ak/!= (az/index worker-threads racer) null)
        (std-thread/join (az/unwrap (az/index worker-threads racer)))
        (set! (az/index worker-threads racer) null)))
    (set! worker-thread-count 0)
    (ak/atomicStore :u8 (ak/& worker-started) 0 :.release)))
