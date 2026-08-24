(ns racing-game.simulation
  "Flecs-owned, fixed-step combat race shared by development and standalone."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [racing-game.telemetry :as telemetry]
            [racing-game.track :as track]
            [racing-game.protocol :as protocol]
            [racing-game.worker :as worker]))

(az/defconst racer-count :usize 8)

(az/defconst hazard-capacity :usize 32)

(az/defconst fixed-step :f32 0.008333333)

(az/defconst ordinary-thought-ticks :u64 40)

(az/defconst lap-count :u16 3)

(az/defconst item-none :u8 0)

(az/defconst item-bolt :u8 1)

(az/defconst item-trap :u8 2)

(az/defconst item-boost :u8 3)

(az/defconst item-shield :u8 4)

(az/defconst item-pulse :u8 5)

(az/defconst item-surge :u8 6)

(az/defconst action-hold :u8 0)

(az/defconst action-use :u8 1)

(az/defconst replay-capacity :usize 512)

(az/defstruct Racer
  "Authoritative sparse Flecs component for one physical racer."
  {:layout :extern}
  [[:id :u8]
   [:rank :u8]
   [:lap :u16]
   [:finished :bool]
   [:item :u8]
   [:shielded :bool]
   [:reserved :u8]
   [:progress :f32]
   [:lane :f32]
   [:speed :f32]
   [:x :f32]
   [:y :f32]
   [:heading :f32]
   [:stun_seconds :f32]
   [:boost_seconds :f32]
   [:pickup_cooldown :f32]
   [:finish_tick :u64]])

(az/defstruct RacerBrain
  "Independent intent, persona, scheduler, and telemetry for one AI racer."
  {:layout :extern}
  [[:racer_id :u8]
   [:pace :u8]
   [:item_action :u8]
   [:target :u8]
   [:source :u8]
   [:urgent :bool]
   [:pending :bool]
   [:reserved :u8]
   [:lane_target :f32]
   [:target_speed :f32]
   [:aggression :f32]
   [:patience :f32]
   [:risk :f32]
   [:next_decision_tick :u64]
   [:decision_revision :u64]
   [:decisions :u64]
   [:urgent_decisions :u64]
   [:invalid_decisions :u64]
   [:last_latency_us :u64]
   [:average_latency_us :u64]])

(az/defstruct Hazard
  "One pooled Flecs-owned projectile or trap. Inactive slots retain stable
  entity identities so the steady-state race never allocates."
  {:layout :extern}
  [[:active :bool]
   [:kind :u8]
   [:owner :u8]
   [:target :u8]
   [:progress :f32]
   [:lane :f32]
   [:speed :f32]
   [:arming_seconds :f32]
   [:ttl :f32]
   [:decision_revision :u64]])

(az/defstruct HazardView
  "Clojure- and renderer-readable projection of a pooled combat object."
  {:layout :extern}
  [[:valid :bool]
   [:active :bool]
   [:kind :u8]
   [:owner :u8]
   [:target :u8]
   [:progress :f32]
   [:lane :f32]
   [:decision_revision :u64]
   [:x :f32]
   [:y :f32]])

(az/defstruct RacerView
  "Clojure-readable projection of the real Flecs racer and its private brain."
  {:layout :extern}
  [[:valid :bool]
   [:id :u8]
   [:rank :u8]
   [:lap :u16]
   [:finished :bool]
   [:item :u8]
   [:shielded :bool]
   [:source :u8]
   [:pending :bool]
   [:item_action :u8]
   [:progress :f32]
   [:speed :f32]
   [:x :f32]
   [:y :f32]
   [:heading :f32]
   [:lane_target :f32]
   [:target_speed :f32]
   [:target :u8]
   [:decision_revision :u64]
   [:decisions :u64]
   [:last_latency_us :u64]
   [:average_latency_us :u64]
   [:finish_tick :u64]])

(az/defstruct RaceSnapshot
  "Allocation-free monitoring summary of the live native race."
  {:layout :extern}
  [[:initialized :bool]
   [:paused :bool]
   [:replay_active :bool]
   [:replay_count :u16]
   [:replay_cursor :u16]
   [:tick :u64]
   [:race_seed :u64]
   [:racers :u8]
   [:finished :u8]
   [:leader :u8]
   [:leader_lap :u16]
   [:leader_progress :f32]
   [:decisions :u64]
   [:urgent_decisions :u64]
   [:invalid_decisions :u64]
   [:items_used :u64]
   [:hits :u64]
   [:active_hazards :u8]
   [:hazards_spawned :u64]
   [:world_address :usize]])

(az/defstruct ReplayIntent
  "One validated intent captured from a prior deterministic race."
  {:layout :extern}
  [[:valid :bool]
   [:accepted :bool]
   [:urgent :bool]
   [:racer :u8]
   [:rank :u8]
   [:lap :u16]
   [:item :u8]
   [:action :u8]
   [:target :u8]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:reserved :u8]
   [:revision :u64]
   [:install_tick :u64]
   [:lane_target :f32]
   [:target_speed :f32]])

(az/defstruct ReplaySummary
  "Inspectable state of the bounded native replay stream."
  {:layout :extern}
  [[:active :bool]
   [:loaded :u16]
   [:installed :u16]
   [:remaining :u16]])

(az/defvar world [:optional [:* flecs/ecs_world_t]] null)

(az/defvar racer-component :u64 0)

(az/defvar brain-component :u64 0)

(az/defvar hazard-component :u64 0)

(az/defvar entities [:array 8 :u64] (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))

(az/defvar hazard-entities [:array 32 :u64]
  (std-mem/zeroes (az/type [:array 32 :u64])))

(az/defvar initialized false)

(az/defvar paused false)

(az/defvar simulation-tick :u64 0)

(az/defvar item-use-count :u64 0)

(az/defvar hit-count :u64 0)

(az/defvar hazard-spawn-count :u64 0)

(az/defvar race-epoch :u64 1)

(az/defvar race-seed :u64 0)

(az/defvar decision-sequence :u64 0)

(az/defvar replay-intents [:array 512 ReplayIntent]
  (std-mem/zeroes (az/type [:array 512 ReplayIntent])))

(az/defvar replay-count :usize 0)

(az/defvar replay-cursor :usize 0)

(az/defvar replay-active false)

(az/defn next-decision-revision!
  {:export false :implicit-return true}
  :-
  :u64
  []
  (do
    (set! decision-sequence (+ decision-sequence 1))
    decision-sequence))

(az/defn clear-replay!
  "Clear the loaded replay without modifying the current race."
  {:attrs #{:public}}
  :-
  :void
  []
  (set! replay-active false)
  (set! replay-count 0)
  (set! replay-cursor 0)
  (set! replay-intents
        (std-mem/zeroes (az/type [:array 512 ReplayIntent]))))

(az/defn append-replay-intent!
  "Append one validated, schema-compatible intent in install-tick order."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[intent ReplayIntent]]
  (let [ordered
        (or (ak/== replay-count 0)
            (<= (az/field (az/index replay-intents (- replay-count 1))
                          install_tick)
                (az/field intent install_tick)))
        valid
        (and (az/field intent valid)
             (az/field intent accepted)
             (< (az/field intent racer) racer-count)
             (< (az/field intent target) racer-count)
             (<= (az/field intent action) action-use)
             (< replay-count replay-capacity)
             ordered
             (ak/== (az/field intent observation_schema)
                    protocol/observation-schema-version)
             (ak/== (az/field intent action_schema)
                    protocol/action-schema-version)
             (>= (az/field intent lane_target) -0.075)
             (<= (az/field intent lane_target) 0.075)
             (>= (az/field intent target_speed) 0.04)
             (<= (az/field intent target_speed) 0.12))]
    (when valid
      (set! (az/index replay-intents replay-count) intent)
      (set! replay-count (+ replay-count 1)))
    valid))

(az/defn replay-summary
  {:attrs #{:public :implicit-return}}
  :-
  ReplaySummary
  []
  (ReplaySummary
   {:active replay-active
    :loaded (ak/intCast replay-count)
    :installed (ak/intCast replay-cursor)
    :remaining (ak/intCast (- replay-count replay-cursor))}))

(az/defn register-component
  {:export false}
  :-
  :u64
  [[flecs-world [:* flecs/ecs_world_t]]
   [component-name [:pointer {:size :c :const? true} :u8]]
   [byte-size :usize]
   [byte-alignment :usize]]
  (let [entity-desc (flecs/ecs_entity_desc_t {:name component-name})
        component-entity (flecs/ecs_entity_init flecs-world (ak/& entity-desc))
        type-info (flecs/ecs_type_info_t
                   {:size (ak/intCast byte-size)
                    :alignment (ak/intCast byte-alignment)})
        component-desc (flecs/ecs_component_desc_t
                        {:entity component-entity :type type-info})]
    (flecs/ecs_component_init flecs-world (ak/& component-desc))))

(az/defn racer-pointer
  {:export false :implicit-return true}
  :-
  [:* Racer]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               racer-component (ak/sizeOf Racer))
      (az/cast [:* Racer])))

(az/defn brain-pointer
  {:export false :implicit-return true}
  :-
  [:* RacerBrain]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               brain-component (ak/sizeOf RacerBrain))
      (az/cast [:* RacerBrain])))

(az/defn install-replay-intents!
  "Install every recorded intent due on this exact fixed simulation tick."
  {:export false}
  :-
  :void
  []
  (ak/while
   (and replay-active
        (< replay-cursor replay-count)
        (<= (az/field (az/index replay-intents replay-cursor) install_tick)
            simulation-tick))
    (let [intent (az/index replay-intents replay-cursor)
          racer-index (ak/as :usize (ak/intCast (az/field intent racer)))
          racer (racer-pointer racer-index)
          brain (brain-pointer racer-index)]
      (set! (az/field (az/deref brain) lane_target)
            (az/field intent lane_target))
      (set! (az/field (az/deref brain) target_speed)
            (az/field intent target_speed))
      (set! (az/field (az/deref brain) target) (az/field intent target))
      (set! (az/field (az/deref brain) item_action) (az/field intent action))
      (set! (az/field (az/deref brain) source) telemetry/source-replay)
      (set! (az/field (az/deref brain) urgent) false)
      (set! (az/field (az/deref brain) pending) false)
      (set! (az/field (az/deref brain) decision_revision)
            (az/field intent revision))
      (set! (az/field (az/deref brain) decisions)
            (+ (az/field (az/deref brain) decisions) 1))
      (when (az/field intent urgent)
        (set! (az/field (az/deref brain) urgent_decisions)
              (+ (az/field (az/deref brain) urgent_decisions) 1)))
      (set! (az/field (az/deref brain) next_decision_tick)
            (+ simulation-tick ordinary-thought-ticks))
      (telemetry/record!
       (telemetry/DecisionLog
        {:valid true :racer_id (az/field intent racer)
         :source telemetry/source-replay :accepted true
         :urgent (az/field intent urgent)
         :prompt_truncated false :response_truncated false
         :rank (az/field intent rank) :item (az/field intent item)
         :target (az/field intent target) :action (az/field intent action)
         :observation_schema (az/field intent observation_schema)
         :action_schema (az/field intent action_schema)
         :validation_code 0 :deadline_status 0 :lap (az/field intent lap)
         :input_token_count 0 :output_token_count 0
         :prompt_byte_count 0 :response_byte_count 0
         :action_head_training_revision
         protocol/action-head-training-revision
         :model_fingerprint protocol/model-fingerprint
         :action_head_fingerprint protocol/action-head-fingerprint
         :sampler_state 0 :revision (az/field intent revision)
         :race_epoch race-epoch
         :enqueue_tick (az/field intent install_tick)
         :install_tick simulation-tick
         :simulation_tick (az/field intent install_tick)
         :queue_us 0 :prefill_us 0 :decode_us 0 :total_us 0
         :tokens_per_second 0.0
         :progress (az/field (az/deref racer) progress)
         :speed (az/field (az/deref racer) speed)
         :lane_target (az/field intent lane_target)
         :target_speed (az/field intent target_speed)
         :input_tokens (std-mem/zeroes (az/type [:array 64 :u32]))
         :output_tokens (std-mem/zeroes (az/type [:array 16 :u32]))
         :prompt_bytes (std-mem/zeroes (az/type [:array 384 :u8]))
         :response_bytes (std-mem/zeroes (az/type [:array 96 :u8]))}))
      (set! replay-cursor (+ replay-cursor 1)))))

(az/defn hazard-pointer
  {:export false :implicit-return true}
  :-
  [:* Hazard]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index hazard-entities index)
                               hazard-component (ak/sizeOf Hazard))
      (az/cast [:* Hazard])))

(az/defn wrapped-distance
  {:export false :implicit-return true}
  :-
  :f32
  [[a :f32]
   [b :f32]]
  (let [distance (ak/abs (- a b))]
    (ak/min distance (- 1.0 distance))))

(az/defn hit-racer!
  "Apply visible counterplay and immediately schedule a fresh thought for the
  struck racer. Shields absorb exactly one hit."
  {:export false}
  :-
  :bool
  [[target-index :usize]
   [stun-seconds :f32]]
  (let [target (racer-pointer target-index)
        target-brain (brain-pointer target-index)
        ^{:var true :zig/type :bool} landed false]
    (if (az/field (az/deref target) shielded)
      (set! (az/field (az/deref target) shielded) false)
      (do
        (set! (az/field (az/deref target) stun_seconds)
              (ak/max (az/field (az/deref target) stun_seconds)
                      stun-seconds))
        (set! hit-count (+ hit-count 1))
        (set! landed true)))
    (set! (az/field (az/deref target-brain) urgent) true)
    (set! (az/field (az/deref target-brain) next_decision_tick)
          simulation-tick)
    landed))

(az/defn spawn-hazard!
  "Activate one preallocated Flecs combat object without allocating."
  {:export false :implicit-return true}
  :-
  :bool
  [[owner-index :usize]
   [kind :u8]
   [target :u8]]
  (let [owner (racer-pointer owner-index)
        owner-brain (brain-pointer owner-index)
        ^{:var true :zig/type :bool} spawned false]
    (dotimes [slot hazard-capacity]
      (when (ak/! spawned)
        (let [hazard (hazard-pointer slot)]
          (when (ak/! (az/field (az/deref hazard) active))
            (set! (az/field (az/deref hazard) active) true)
            (set! (az/field (az/deref hazard) kind) kind)
            (set! (az/field (az/deref hazard) owner) (ak/intCast owner-index))
            (set! (az/field (az/deref hazard) target) target)
            (set! (az/field (az/deref hazard) progress)
                  (az/field (az/deref owner) progress))
            (set! (az/field (az/deref hazard) lane)
                  (az/field (az/deref owner) lane))
            (set! (az/field (az/deref hazard) speed)
                  (if (ak/== kind item-bolt) 0.34 0.0))
            (set! (az/field (az/deref hazard) arming_seconds)
                  (if (ak/== kind item-bolt) 0.08 0.25))
            (set! (az/field (az/deref hazard) ttl)
                  (if (ak/== kind item-bolt) 2.4 8.0))
            (set! (az/field (az/deref hazard) decision_revision)
                  (az/field (az/deref owner-brain) decision_revision))
            (set! hazard-spawn-count (+ hazard-spawn-count 1))
            (set! spawned true)))))
    spawned))

(az/defn step-hazards!
  "Move pooled bolts and resolve bolt/trap contact after all racers advance."
  {:export false}
  :-
  :void
  []
  (dotimes [slot hazard-capacity]
    (let [hazard (hazard-pointer slot)]
      (when (az/field (az/deref hazard) active)
        (set! (az/field (az/deref hazard) ttl)
              (- (az/field (az/deref hazard) ttl) fixed-step))
        (set! (az/field (az/deref hazard) arming_seconds)
              (ak/max 0.0
                      (- (az/field (az/deref hazard) arming_seconds)
                         fixed-step)))
        (when (ak/== (az/field (az/deref hazard) kind) item-bolt)
          (set! (az/field (az/deref hazard) progress)
                (mod (+ (az/field (az/deref hazard) progress)
                        (* (az/field (az/deref hazard) speed) fixed-step))
                     1.0)))
        (dotimes [racer-index racer-count]
          (when (and (az/field (az/deref hazard) active)
                     (<= (az/field (az/deref hazard) arming_seconds) 0.0)
                     (ak/!= racer-index
                            (ak/as :usize
                                   (ak/intCast
                                    (az/field (az/deref hazard) owner)))))
            (let [racer (racer-pointer racer-index)
                  progress-distance
                  (wrapped-distance (az/field (az/deref hazard) progress)
                                    (az/field (az/deref racer) progress))
                  lane-distance
                  (ak/abs (- (az/field (az/deref hazard) lane)
                             (az/field (az/deref racer) lane)))
                  ^{:zig/type :f32}
                  progress-radius
                  (if (ak/== (az/field (az/deref hazard) kind) item-bolt)
                    0.012
                    0.009)]
              (when (and (< progress-distance progress-radius)
                         (< lane-distance 0.045))
                (when (hit-racer! racer-index
                                  (if (ak/== (az/field (az/deref hazard) kind)
                                            item-trap)
                                    0.85
                                    0.55))
                  (set! _
                        (telemetry/mark-hit!
                         (az/field (az/deref hazard) owner)
                         (az/field (az/deref hazard) decision_revision))))
                (set! (az/field (az/deref hazard) active) false)))))
        (when (<= (az/field (az/deref hazard) ttl) 0.0)
          (set! (az/field (az/deref hazard) active) false))))))

(az/defn update-position!
  {:export false}
  :-
  :void
  [[racer [:* Racer]]]
  (let [sample (track/pose (az/field (az/deref racer) progress)
                           (az/field (az/deref racer) lane))]
    (set! (az/field (az/deref racer) x) (az/field sample x))
    (set! (az/field (az/deref racer) y) (az/field sample y))
    (set! (az/field (az/deref racer) heading) (az/field sample heading))))

(az/defn absolute-progress
  {:export false :implicit-return true}
  :-
  :f32
  [[racer [:* Racer]]]
  (+ (ak/as :f32 (ak/floatFromInt (az/field (az/deref racer) lap)))
     (az/field (az/deref racer) progress)))

(az/defn complete-lap!
  "Advance exactly one lap and permanently record this racer's finish tick."
  {:export false}
  :-
  :void
  [[racer [:* Racer]]]
  (set! (az/field (az/deref racer) lap)
        (+ (az/field (az/deref racer) lap) 1))
  (when (and (ak/! (az/field (az/deref racer) finished))
             (>= (az/field (az/deref racer) lap) lap-count))
    (set! (az/field (az/deref racer) finished) true)
    (set! (az/field (az/deref racer) finish_tick) simulation-tick)))

(az/defn choose-target
  "Return the nearest unfinished opponent ahead, or the current leader."
  {:export false}
  :-
  :u8
  [[self-index :usize]]
  (let [self (racer-pointer self-index)
        self-total (absolute-progress self)
        ^{:var true :zig/type :u8} chosen (az/field (az/deref self) id)
        ^{:var true :zig/type :f32} best-distance 1000.0]
    (dotimes [other-index racer-count]
      (when (ak/!= other-index self-index)
        (let [other (racer-pointer other-index)
              distance (- (absolute-progress other) self-total)]
          (when (and (ak/! (az/field (az/deref other) finished))
                     (> distance 0.0)
                     (< distance best-distance))
            (set! best-distance distance)
            (set! chosen (az/field (az/deref other) id))))))
    chosen))

(az/defn make-decision!
  "Install one independent tactical intent. This transparent policy is the
  native fallback and training baseline used whenever LLM output is late."
  {:attrs #{:public}}
  :-
  :void
  [[index :usize]
   [urgent :bool]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        aggression (az/field (az/deref brain) aggression)
        risk (az/field (az/deref brain) risk)
        lane-phase (mod (+ (az/field (az/deref brain) decision_revision)
                           (ak/as :u64 (ak/intCast index)))
                        3)
        ^{:zig/type :f32}
        lane-target (cond
                      (ak/== lane-phase 0) -0.075
                      (ak/== lane-phase 1) 0.0
                      :else 0.075)
        target-speed (+ 0.068 (* aggression 0.010) (* risk 0.006))
        target (choose-target index)
        revision (next-decision-revision!)
        should-use (and (ak/!= (az/field (az/deref racer) item) item-none)
                        (or urgent
                            (> aggression 0.62)
                            (ak/== (az/field (az/deref racer) item) item-boost)))]
    (set! (az/field (az/deref brain) lane_target) lane-target)
    (set! (az/field (az/deref brain) target_speed) target-speed)
    (set! (az/field (az/deref brain) target) target)
    (set! (az/field (az/deref brain) item_action)
          (if should-use action-use action-hold))
    (set! (az/field (az/deref brain) source) 0)
    ;; Urgency is an edge-triggered request. Consuming the decision clears it;
    ;; a later native event can set it again without turning every thought into
    ;; an urgent one.
    (set! (az/field (az/deref brain) urgent) false)
    (set! (az/field (az/deref brain) pending) false)
    (set! (az/field (az/deref brain) decision_revision) revision)
    (set! (az/field (az/deref brain) decisions)
          (+ (az/field (az/deref brain) decisions) 1))
    (when urgent
      (set! (az/field (az/deref brain) urgent_decisions)
            (+ (az/field (az/deref brain) urgent_decisions) 1)))
    (set! (az/field (az/deref brain) next_decision_tick)
          (+ simulation-tick ordinary-thought-ticks))
    (telemetry/record-fallback!
     (az/field (az/deref racer) id)
     (az/field (az/deref racer) rank)
     (az/field (az/deref racer) lap)
     (az/field (az/deref racer) item)
     target
     (az/field (az/deref brain) item_action)
     urgent
     revision
     race-epoch
     simulation-tick
     (az/field (az/deref racer) progress)
     (az/field (az/deref racer) speed)
     lane-target
     target-speed)))

(az/defn record-worker-result!
  {:export false}
  :-
  :void
  [[result worker/InferenceResult]
   [accepted :bool]
   [item-action :u8]]
  (let [base
        (telemetry/DecisionLog
         {:valid true
          :racer_id (az/field result racer)
          :source telemetry/source-llm
          :accepted accepted
          :urgent (az/field result urgent)
          :prompt_truncated false
          :response_truncated false
          :rank (az/field result rank)
          :item (az/field result item)
          :target (az/field result target)
          :action item-action
          :observation_schema (az/field result observation_schema)
          :action_schema (az/field result action_schema)
          :validation_code (if accepted 0 1)
          :deadline_status 0
          :lap (az/field result lap)
          :input_token_count (az/field result input_token_count)
          :output_token_count (az/field result output_token_count)
          :prompt_byte_count (az/field result prompt_byte_count)
          :response_byte_count 1
          :action_head_training_revision
          protocol/action-head-training-revision
          :model_fingerprint protocol/model-fingerprint
          :action_head_fingerprint protocol/action-head-fingerprint
          :sampler_state (az/field result sampler_state)
          :revision (az/field result revision)
          :race_epoch (az/field result race_epoch)
          :enqueue_tick (az/field result simulation_tick)
          :install_tick simulation-tick
          :simulation_tick (az/field result simulation_tick)
          :queue_us (az/field result queue_us)
          :prefill_us (az/field result prefill_us)
          :decode_us (az/field result decode_us)
          :total_us (az/field result total_us)
          :tokens_per_second (az/field result tokens_per_second)
          :progress (az/field result progress)
          :speed (az/field result speed)
          :lane_target (az/field result lane_target)
          :target_speed (az/field result target_speed)
          :input_tokens (std-mem/zeroes (az/type [:array 64 :u32]))
          :output_tokens (std-mem/zeroes (az/type [:array 16 :u32]))
          :prompt_bytes (std-mem/zeroes (az/type [:array 384 :u8]))
          :response_bytes (std-mem/zeroes (az/type [:array 96 :u8]))})]
    (telemetry/record-llm!
     base
     (az/slice (az/field result prompt_bytes)
               0 (az/field result prompt_byte_count))
     (az/slice (az/field result response_bytes)
               0 (az/field result output_token_count))
     (az/slice (az/field result input_tokens)
               0 (ak/min 32 (az/field result input_token_count)))
     (az/slice (az/field result output_tokens)
               0 (ak/min 1 (az/field result output_token_count))))))

(az/defn install-worker-result!
  "Install only a result for the current race epoch and outstanding revision."
  {:export false}
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        result (worker/result-for index
                                  (az/field (az/deref brain) decision_revision))]
    (when (and (az/field result valid)
               (ak/== (az/field result race_epoch) race-epoch)
               (ak/== (az/field result observation_schema)
                      protocol/observation-schema-version)
               (ak/== (az/field result action_schema)
                      protocol/action-schema-version)
               (> (az/field result revision)
                  (az/field (az/deref brain) decision_revision)))
      (let [accepted (and (az/field result accepted)
                          (< (az/field result target) racer-count)
                          (>= (az/field result lane_target) -0.075)
                          (<= (az/field result lane_target) 0.075)
                          (>= (az/field result target_speed) 0.04)
                          (<= (az/field result target_speed) 0.12))
            item-action
            (if (and accepted
                     (ak/!= (az/field (az/deref racer) item) item-none)
                     (ak/== (az/field result item_action) action-use))
              action-use
              action-hold)]
        (when accepted
          (set! (az/field (az/deref brain) lane_target)
                (az/field result lane_target))
          (set! (az/field (az/deref brain) target_speed)
                (az/field result target_speed))
          (set! (az/field (az/deref brain) target) (az/field result target))
          (set! (az/field (az/deref brain) item_action) item-action)
          (set! (az/field (az/deref brain) source) telemetry/source-llm))
        (when (ak/! accepted)
          (set! (az/field (az/deref brain) invalid_decisions)
                (+ (az/field (az/deref brain) invalid_decisions) 1)))
        (set! (az/field (az/deref brain) pending) false)
        (set! (az/field (az/deref brain) urgent) false)
        (set! (az/field (az/deref brain) decision_revision)
              (az/field result revision))
        (set! (az/field (az/deref brain) decisions)
              (+ (az/field (az/deref brain) decisions) 1))
        (when (az/field result urgent)
          (set! (az/field (az/deref brain) urgent_decisions)
                (+ (az/field (az/deref brain) urgent_decisions) 1)))
        (set! (az/field (az/deref brain) last_latency_us)
              (az/field result total_us))
        (set! (az/field (az/deref brain) average_latency_us)
              (if (ak/== (az/field (az/deref brain) decisions) 1)
                (az/field result total_us)
                (/ (+ (az/field (az/deref brain) average_latency_us)
                      (az/field result total_us))
                   2)))
        (set! (az/field (az/deref brain) next_decision_tick)
              (+ simulation-tick ordinary-thought-ticks))
        (record-worker-result! result accepted item-action)))))

(az/defn submit-worker-request!
  {:export false :implicit-return true}
  :-
  :bool
  [[index :usize]
   [urgent :bool]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        revision (next-decision-revision!)
        aggression (az/field (az/deref brain) aggression)
        persona (cond
                  (< aggression 0.48) (ak/as :u8 0)
                  (< aggression 0.72) (ak/as :u8 1)
                  :else (ak/as :u8 2))
        request
        (worker/InferenceRequest
         {:valid true
          :racer (az/field (az/deref racer) id)
          :rank (az/field (az/deref racer) rank)
          :lap (az/field (az/deref racer) lap)
          :item (az/field (az/deref racer) item)
          :target (choose-target index)
          :persona persona
          :urgent urgent
          :observation_schema protocol/observation-schema-version
          :action_schema protocol/action-schema-version
          :revision revision
          :race_epoch race-epoch
          :simulation_tick simulation-tick
          :progress (az/field (az/deref racer) progress)
          :speed (az/field (az/deref racer) speed)
          :enqueue_seconds 0.0})]
    (if (worker/submit! request)
      (do
        (set! (az/field (az/deref brain) pending) true)
        (set! (az/field (az/deref brain) next_decision_tick)
              (+ simulation-tick ordinary-thought-ticks))
        true)
      false)))

(az/defn apply-item!
  {:export false}
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        item (az/field (az/deref racer) item)]
    (when (and (ak/!= item item-none)
               (ak/== (az/field (az/deref brain) item_action) action-use))
      (let [^{:var true :zig/type :bool} used true]
        (cond
          (ak/== item item-boost)
          (set! (az/field (az/deref racer) boost_seconds) 1.25)

          (ak/== item item-shield)
          (set! (az/field (az/deref racer) shielded) true)

          (or (ak/== item item-bolt) (ak/== item item-trap))
          (set! used
                (spawn-hazard! index item
                               (az/field (az/deref brain) target)))

          (ak/== item item-pulse)
          (dotimes [target-index racer-count]
            (when (ak/!= target-index index)
              (let [target (racer-pointer target-index)]
                (when (< (wrapped-distance
                          (az/field (az/deref racer) progress)
                          (az/field (az/deref target) progress))
                         0.10)
                  (when (hit-racer! target-index 0.35)
                    (set! _
                          (telemetry/mark-hit!
                           (az/field (az/deref racer) id)
                           (az/field (az/deref brain)
                                     decision_revision))))))))

          (ak/== item item-surge)
          (let [advanced (+ (az/field (az/deref racer) progress) 0.045)]
            (set! (az/field (az/deref racer) progress) (mod advanced 1.0))
            (when (>= advanced 1.0)
              (complete-lap! racer)))

          :else
          (set! used false))
        (when used
          (set! _
                (telemetry/mark-item-used!
                 (az/field (az/deref racer) id)
                 (az/field (az/deref brain) decision_revision)))
          (set! (az/field (az/deref racer) item) item-none)
          (set! (az/field (az/deref brain) item_action) action-hold)
          (set! item-use-count (+ item-use-count 1)))))))

(az/defn collect-item!
  {:export false}
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)]
    (when (and (ak/== (az/field (az/deref racer) item) item-none)
               (<= (az/field (az/deref racer) pickup_cooldown) 0.0)
               (< (wrapped-distance (az/field (az/deref racer) progress) 0.25)
                  0.008))
      (set! (az/field (az/deref racer) item)
            (+ 1 (ak/as :u8
                        (ak/intCast
                         (mod (+ (ak/as :u64 (ak/intCast index))
                                 (ak/as :u64
                                        (ak/intCast
                                         (az/field (az/deref racer) lap)))
                                 race-seed)
                              6)))))
      (set! (az/field (az/deref racer) pickup_cooldown) 1.0)
      (let [brain (brain-pointer index)]
        (set! (az/field (az/deref brain) next_decision_tick) simulation-tick)
        (set! (az/field (az/deref brain) urgent) true)))))

(az/defn step-racer!
  {:export false}
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)]
    (when (ak/! replay-active)
      (install-worker-result! index))
    (when (and (ak/! replay-active)
               (ak/! (az/field (az/deref racer) finished))
               (ak/! (az/field (az/deref brain) pending))
               (>= simulation-tick
                   (az/field (az/deref brain) next_decision_tick)))
      (let [urgent (az/field (az/deref brain) urgent)]
        (when (ak/! (submit-worker-request! index urgent))
          (make-decision! index urgent))))
    (apply-item! index)
    (when (ak/! (az/field (az/deref racer) finished))
      (let [lane-delta (- (az/field (az/deref brain) lane_target)
                          (az/field (az/deref racer) lane))
            stunned (> (az/field (az/deref racer) stun_seconds) 0.0)
            boosted (> (az/field (az/deref racer) boost_seconds) 0.0)
            ^{:zig/type :f32}
            boost-extra (if boosted 0.035 0.0)
            ^{:zig/type :f32}
            desired-speed (if stunned
                            0.025
                            (+ (az/field (az/deref brain) target_speed)
                               boost-extra))
            speed-delta (- desired-speed (az/field (az/deref racer) speed))]
        (set! (az/field (az/deref racer) lane)
              (+ (az/field (az/deref racer) lane)
                 (* lane-delta fixed-step 3.5)))
        (set! (az/field (az/deref racer) speed)
              (+ (az/field (az/deref racer) speed)
                 (* speed-delta fixed-step 2.8)))
        (set! (az/field (az/deref racer) progress)
              (+ (az/field (az/deref racer) progress)
                 (* (az/field (az/deref racer) speed) fixed-step)))
        (when (>= (az/field (az/deref racer) progress) 1.0)
          (set! (az/field (az/deref racer) progress)
                (- (az/field (az/deref racer) progress) 1.0))
          (complete-lap! racer))
        (set! (az/field (az/deref racer) stun_seconds)
              (ak/max 0.0 (- (az/field (az/deref racer) stun_seconds) fixed-step)))
        (set! (az/field (az/deref racer) boost_seconds)
              (ak/max 0.0 (- (az/field (az/deref racer) boost_seconds) fixed-step)))
        (set! (az/field (az/deref racer) pickup_cooldown)
              (ak/max 0.0 (- (az/field (az/deref racer) pickup_cooldown) fixed-step)))
        (collect-item! index)
        (update-position! racer)))))

(az/defn update-ranks!
  {:export false}
  :-
  :void
  []
  (dotimes [index racer-count]
    (let [racer (racer-pointer index)
          ^{:var true :zig/type :u8} rank 1]
      (dotimes [other-index racer-count]
        (when (ak/!= index other-index)
          (let [other (racer-pointer other-index)
                other-ahead
                (cond
                  (and (az/field (az/deref other) finished)
                       (az/field (az/deref racer) finished))
                  (or (< (az/field (az/deref other) finish_tick)
                         (az/field (az/deref racer) finish_tick))
                      (and (ak/== (az/field (az/deref other) finish_tick)
                                  (az/field (az/deref racer) finish_tick))
                           (< (az/field (az/deref other) id)
                              (az/field (az/deref racer) id))))

                  (az/field (az/deref other) finished) true
                  (az/field (az/deref racer) finished) false

                  :else
                  (> (absolute-progress other) (absolute-progress racer)))]
            (when other-ahead
              (set! rank (+ rank 1))))))
      (set! (az/field (az/deref racer) rank) rank))))

(az/defn initialize!
  "Create the Flecs world and exactly eight independently scheduled AI racers."
  :-
  :bool
  []
  (when (ak/! initialized)
    (set! world (flecs/ecs_init))
    (let [flecs-world (az/unwrap world)]
      (set! racer-component
            (register-component flecs-world "RacingRacer"
                                (ak/sizeOf Racer) (ak/alignOf Racer)))
      (set! brain-component
            (register-component flecs-world "RacingBrain"
                                (ak/sizeOf RacerBrain) (ak/alignOf RacerBrain)))
      (set! hazard-component
            (register-component flecs-world "RacingHazard"
                                (ak/sizeOf Hazard) (ak/alignOf Hazard)))
      (flecs/ecs_add_id flecs-world racer-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world brain-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world hazard-component flecs/EcsSparse)
      (dotimes [index racer-count]
        (let [entity (flecs/ecs_new flecs-world)
              identifier (ak/as :u8 (ak/intCast index))
              seed-slot
              (ak/as :usize
                     (ak/intCast
                      (mod race-seed
                           (ak/as :u64 (ak/intCast racer-count)))))
              grid-index (mod (+ index seed-slot) racer-count)
              progress (* (ak/as :f32 (ak/floatFromInt grid-index)) 0.011)
              ^{:zig/type :f32}
              lane (cond
                     (ak/== (mod grid-index 3) 0) -0.075
                     (ak/== (mod grid-index 3) 1) 0.0
                     :else 0.075)
              racer (Racer {:id identifier
                            :rank (+ identifier 1)
                            :lap 0
                            :finished false
                            :item item-none
                            :shielded false
                            :reserved 0
                            :progress progress
                            :lane lane
                            :speed 0.035
                            :x 0.0
                            :y 0.0
                            :heading 0.0
                            :stun_seconds 0.0
                            :boost_seconds 0.0
                            :pickup_cooldown 0.0
                            :finish_tick 0})
              brain (RacerBrain
                     {:racer_id identifier
                      :pace 1
                      :item_action action-hold
                      :target identifier
                      :source 0
                      :urgent true
                      :pending false
                      :reserved 0
                      :lane_target lane
                      :target_speed 0.07
                      :aggression (+ 0.28 (* 0.085 (ak/as :f32 (ak/floatFromInt index))))
                      :patience (- 0.86 (* 0.07 (ak/as :f32 (ak/floatFromInt index))))
                      :risk (+ 0.20 (* 0.075 (ak/as :f32 (ak/floatFromInt (mod (+ index 3) 8)))))
                      :next_decision_tick (ak/as :u64 (ak/intCast (* index 5)))
                      :decision_revision 0
                      :decisions 0
                      :urgent_decisions 0
                      :invalid_decisions 0
                      :last_latency_us 0
                      :average_latency_us 0})]
          (set! (az/index entities index) entity)
          (flecs/ecs_set_id flecs-world entity racer-component
                            (ak/sizeOf Racer) (ak/& racer))
          (flecs/ecs_set_id flecs-world entity brain-component
                            (ak/sizeOf RacerBrain) (ak/& brain))
          (update-position! (racer-pointer index))))
      (dotimes [slot hazard-capacity]
        (let [entity (flecs/ecs_new flecs-world)
              hazard (std-mem/zeroes (az/type Hazard))]
          (set! (az/index hazard-entities slot) entity)
          (flecs/ecs_set_id flecs-world entity hazard-component
                            (ak/sizeOf Hazard) (ak/& hazard)))))
    (set! initialized true))
  initialized)

(az/defn step!
  "Advance one deterministic 120 Hz simulation tick."
  :-
  :void
  []
  (set! _ (initialize!))
  (when (ak/! paused)
    (when replay-active
      (install-replay-intents!))
    (dotimes [index racer-count]
      (step-racer! index))
    (step-hazards!)
    (update-ranks!)
    (dotimes [index racer-count]
      (let [racer (racer-pointer index)]
        (telemetry/resolve-due-outcomes!
         (az/field (az/deref racer) id)
         simulation-tick
         (az/field (az/deref racer) rank)
         (az/field (az/deref racer) lap)
         (az/field (az/deref racer) progress)
         (az/field (az/deref racer) finished))))
    (set! _ (flecs/ecs_progress world fixed-step))
    (set! simulation-tick (+ simulation-tick 1))))

(az/defn step-many!
  "Headless deterministic stepping used by nREPL and standalone tests."
  :-
  :void
  [[ticks :u32]]
  (dotimes [_ ticks]
    (step!)))

(az/defn racer-view
  "Inspect one AI using its stable zero-based racer id."
  :-
  RacerView
  [[identifier :u8]]
  (if (or (ak/! initialized) (>= identifier racer-count))
    (RacerView {:valid false :id identifier :rank 0 :lap 0 :finished false
                :item 0 :shielded false :source 0 :pending false :item_action 0
                :progress 0.0 :speed 0.0
                :x 0.0 :y 0.0 :heading 0.0 :lane_target 0.0
                :target_speed 0.0 :target 0 :decision_revision 0
                :decisions 0 :last_latency_us 0 :average_latency_us 0
                :finish_tick 0})
    (let [index (ak/as :usize (ak/intCast identifier))
          racer (racer-pointer index)
          brain (brain-pointer index)]
      (RacerView
       {:valid true
        :id identifier
        :rank (az/field (az/deref racer) rank)
        :lap (az/field (az/deref racer) lap)
        :finished (az/field (az/deref racer) finished)
        :item (az/field (az/deref racer) item)
        :shielded (az/field (az/deref racer) shielded)
        :source (az/field (az/deref brain) source)
        :pending (az/field (az/deref brain) pending)
        :item_action (az/field (az/deref brain) item_action)
        :progress (az/field (az/deref racer) progress)
        :speed (az/field (az/deref racer) speed)
        :x (az/field (az/deref racer) x)
        :y (az/field (az/deref racer) y)
        :heading (az/field (az/deref racer) heading)
        :lane_target (az/field (az/deref brain) lane_target)
        :target_speed (az/field (az/deref brain) target_speed)
        :target (az/field (az/deref brain) target)
        :decision_revision (az/field (az/deref brain) decision_revision)
        :decisions (az/field (az/deref brain) decisions)
        :last_latency_us (az/field (az/deref brain) last_latency_us)
        :average_latency_us (az/field (az/deref brain) average_latency_us)
        :finish_tick (az/field (az/deref racer) finish_tick)}))))

(az/defn hazard-view
  "Inspect one stable pooled combat-object slot."
  {:attrs #{:public :implicit-return}}
  :-
  HazardView
  [[slot :usize]]
  (if (or (ak/! initialized) (>= slot hazard-capacity))
    (HazardView {:valid false :active false :kind 0 :owner 0 :target 0
                 :progress 0.0 :lane 0.0 :decision_revision 0
                 :x 0.0 :y 0.0})
    (let [hazard (hazard-pointer slot)
          sample (track/pose (az/field (az/deref hazard) progress)
                             (az/field (az/deref hazard) lane))]
      (HazardView
       {:valid true
        :active (az/field (az/deref hazard) active)
        :kind (az/field (az/deref hazard) kind)
        :owner (az/field (az/deref hazard) owner)
        :target (az/field (az/deref hazard) target)
        :progress (az/field (az/deref hazard) progress)
        :lane (az/field (az/deref hazard) lane)
        :decision_revision (az/field (az/deref hazard) decision_revision)
        :x (az/field sample x)
        :y (az/field sample y)}))))

(az/defn snapshot
  "Return real race ordering and aggregate decision/combat telemetry."
  :-
  RaceSnapshot
  []
  (set! _ (initialize!))
  (let [^{:var true :zig/type :u8} finished 0
        ^{:var true :zig/type :u8} leader 0
        ^{:var true :zig/type :u16} leader-lap 0
        ^{:var true :zig/type :f32} leader-progress 0.0
        ^{:var true :zig/type :u64} decisions 0
        ^{:var true :zig/type :u64} urgent-decisions 0
        ^{:var true :zig/type :u64} invalid-decisions 0
        ^{:var true :zig/type :u8} active-hazards 0]
    (dotimes [index racer-count]
      (let [racer (racer-pointer index)
            brain (brain-pointer index)]
        (when (az/field (az/deref racer) finished)
          (set! finished (+ finished 1)))
        (when (ak/== (az/field (az/deref racer) rank) 1)
          (set! leader (az/field (az/deref racer) id))
          (set! leader-lap (az/field (az/deref racer) lap))
          (set! leader-progress (az/field (az/deref racer) progress)))
        (set! decisions (+ decisions (az/field (az/deref brain) decisions)))
        (set! urgent-decisions
              (+ urgent-decisions (az/field (az/deref brain) urgent_decisions)))
        (set! invalid-decisions
              (+ invalid-decisions (az/field (az/deref brain) invalid_decisions)))))
    (dotimes [slot hazard-capacity]
      (when (az/field (az/deref (hazard-pointer slot)) active)
        (set! active-hazards (+ active-hazards 1))))
    (RaceSnapshot
     {:initialized initialized
      :paused paused
      :replay_active replay-active
      :replay_count (ak/intCast replay-count)
      :replay_cursor (ak/intCast replay-cursor)
      :tick simulation-tick
      :race_seed race-seed
      :racers (ak/intCast racer-count)
      :finished finished
      :leader leader
      :leader_lap leader-lap
      :leader_progress leader-progress
      :decisions decisions
      :urgent_decisions urgent-decisions
      :invalid_decisions invalid-decisions
      :items_used item-use-count
      :hits hit-count
      :active_hazards active-hazards
      :hazards_spawned hazard-spawn-count
      :world_address (if (ak/== world null)
                       0
                       (ak/intFromPtr (az/unwrap world)))})))

(az/defn toggle-paused!
  :-
  :bool
  []
  (set! paused (ak/! paused))
  paused)

(az/defn set-race-seed!
  "Choose the deterministic starting-grid and pickup permutation used by the
  next explicit `reset!`. The running world is never mutated implicitly."
  {:attrs #{:public}}
  :-
  :void
  [[seed :u64]]
  (set! race-seed seed))

(az/defn reset!
  "Explicitly recreate the Flecs race. Ordinary hot reload never calls this."
  :-
  :void
  []
  (when (ak/!= world null)
    (set! _ (flecs/ecs_fini world)))
  (set! world null)
  (set! initialized false)
  (set! paused false)
  (set! replay-active false)
  (set! replay-cursor 0)
  (set! simulation-tick 0)
  (set! item-use-count 0)
  (set! hit-count 0)
  (set! hazard-spawn-count 0)
  (set! race-epoch (+ race-epoch 1))
  (set! entities (az/array-init [:array 8 :u64] [0 0 0 0 0 0 0 0]))
  (set! hazard-entities (std-mem/zeroes (az/type [:array 32 :u64])))
  (telemetry/reset!)
  (set! _ (initialize!)))

(az/defn start-replay!
  "Reset the race and install only the previously loaded intent stream."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (let [available (> replay-count 0)]
    (when available
      (reset!)
      (set! replay-cursor 0)
      (set! replay-active true))
    available))

(az/defn stop-replay!
  "Leave the current replayed world intact and resume normal cognition."
  {:attrs #{:public}}
  :-
  :void
  []
  (set! replay-active false))

(az/defn shutdown!
  :-
  :void
  []
  (when (ak/!= world null)
    (set! _ (flecs/ecs_fini world)))
  (set! world null)
  (set! replay-active false)
  (set! initialized false))
