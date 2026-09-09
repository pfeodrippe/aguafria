(ns racing-game.simulation
  "Flecs-owned, fixed-step combat race shared by development and standalone."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [aguafria-examples-native.bindings.runtime :as runtime]
            [racing-game.telemetry :as telemetry]
            [racing-game.track :as track]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.physics-track :as terrain]
            [racing-game.track-barriers :as barriers]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-recovery :as recovery]
            [racing-game.vehicle-turnaround :as turnaround]
            [racing-game.race-status :as status]
            [racing-game.lap-timing :as lap-timing]
            [aguafria-examples-native.bindings.box3d :as b3]
            [racing-game.protocol :as protocol]
            [racing-game.worker :as worker]))

(az/defconst racer-count :usize protocol/racer-count)

(az/defconst team-count :usize protocol/team-count)

(az/defconst team-worker-offset :usize protocol/racer-count)

(az/defconst no-pit-occupant :u8 255)

(az/defconst pit-state-track :u8 0)

(az/defconst pit-state-called :u8 1)

(az/defconst pit-state-servicing :u8 2)

(az/defconst pit-state-exiting :u8 3)

(az/defconst radio-none :u8 0)

(az/defconst radio-tires-wearing :u8 1)

(az/defconst radio-pit-confirmed :u8 2)

(az/defconst radio-box-occupied :u8 3)

(az/defconst radio-boxing-now :u8 4)

(az/defconst radio-fresh-tires :u8 5)

(az/defconst radio-car-damaged :u8 6)

(az/defconst radio-repair-confirmed :u8 7)

(az/defconst radio-car-repaired :u8 8)

(az/defconst radio-stay-out :u8 9)

(az/defconst radio-retired :u8 10)

(az/defconst radio-outside-world :u8 11)

(az/defconst radio-source-none :u8 0)

(az/defconst radio-source-driver :u8 1)

(az/defconst radio-source-strategist :u8 2)

(az/defconst radio-source-race-control :u8 3)

(az/defconst team-action-hold :u8 0)

(az/defconst team-action-driver-a :u8 1)

(az/defconst team-action-driver-b :u8 2)

(az/defconst tire-warning-threshold :f32 0.72)

(az/defconst tire-pit-threshold :f32 0.46)

(az/defconst damage-warning-threshold :f32 0.20)

(az/defconst damage-pit-threshold :f32 0.60)

(az/defconst pit-entry-progress :f32 0.94)

(az/defconst pit-service-seconds :f32 1.25)

(az/defconst pit-repair-extra-seconds :f32 1.75)

(az/defconst team-decision-cadence-ticks :u64 120)

(az/defconst team-radio-history-per-team :usize 32)

(az/defconst hazard-capacity :usize 32)

(az/defconst fixed-step :f32 0.008333333)

(az/defconst ordinary-thought-ticks :u64 40)

(az/defconst pressured-thought-ticks :u64 48)

(az/defconst overloaded-thought-ticks :u64 60)

(az/defconst critical-thought-ticks :u64 80)

(az/defconst ordinary-decision-deadline-ticks :u64 720)

(az/defconst urgent-decision-deadline-ticks :u64 600)

(az/defconst deadline-on-time :u8 0)

(az/defconst deadline-expired :u8 1)

(az/defconst lap-count :u16 3)

(az/defconst checkpoint-count :u8 4)

(az/defconst race-state-countdown :u8 0)

(az/defconst race-state-running :u8 1)

(az/defconst race-state-finished :u8 2)

(az/defconst target-lane-left :u8 0)

(az/defconst target-lane-same :u8 1)

(az/defconst target-lane-right :u8 2)

(az/defconst tactical-status-clear :u8 0)

(az/defconst tactical-status-hazard :u8 1)

(az/defconst tactical-status-stunned :u8 2)

(az/defconst tactical-status-shielded :u8 3)

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

(az/defconst replay-file-header-bytes :usize 32)

(az/defconst replay-file-entry-bytes :usize 32)

(az/defconst replay-file-version :u16 2)

(az/defconst replay-file-ok :u32 0)

(az/defconst replay-file-open-failed :u32 1)

(az/defconst replay-file-invalid-size :u32 2)

(az/defconst replay-file-invalid-header :u32 3)

(az/defconst replay-file-incompatible :u32 4)

(az/defconst replay-file-invalid-intent :u32 5)

(az/defstruct Racer
  "Authoritative sparse Flecs component for one physical racer."
  {:layout :extern}
  [[:id :u8]
   [:rank :u8]
   [:lap :u16]
   [:finished :bool]
   [:item :u8]
   [:shielded :bool]
   [:team :u8]
   [:pit_state :u8]
   [:tire_stage :u8]
   [:pit_waiting :bool]
   [:pit_stops :u8]
   [:damage_stage :u8]
   [:radio_code :u8]
   [:radio_source :u8]
   [:tire_condition :f32]
   [:damage :f32]
   [:pit_seconds :f32]
   [:progress :f32]
   [:lane :f32]
   [:speed :f32]
   [:x :f32]
   [:y :f32]
   [:heading :f32]
   [:stun_seconds :f32]
   [:boost_seconds :f32]
   [:pickup_cooldown :f32]
   [:radio_revision :u64]
   [:finish_tick :u64]])

(az/defstruct Team
  "One stable Flecs team entity coordinating its two drivers and pit box."
  {:layout :extern}
  [[:id :u8]
   [:driver_a :u8]
   [:driver_b :u8]
   [:pit_occupant :u8]
   [:instruction :u8]
   [:radio_code :u8]
   [:radio_target :u8]
   [:pending :bool]
   [:pit_stops :u16]
   [:reserved :u16]
   [:pending_revision :u64]
   [:pending_tick :u64]
   [:decision_revision :u64]
   [:next_decision_tick :u64]
   [:decisions :u64]
   [:invalid_decisions :u64]
   [:last_latency_us :u64]
   [:average_latency_us :u64]
   [:radio_sequence :u64]])

(az/defstruct TeamView
  "Inspectable team strategy and pit-box state."
  {:layout :extern}
  [[:valid :bool]
   [:id :u8]
   [:driver_a :u8]
   [:driver_b :u8]
   [:pit_occupant :u8]
   [:instruction :u8]
   [:radio_code :u8]
   [:radio_target :u8]
   [:pending :bool]
   [:pit_stops :u16]
   [:decision_revision :u64]
   [:decisions :u64]
   [:invalid_decisions :u64]
   [:last_latency_us :u64]
   [:average_latency_us :u64]
   [:radio_sequence :u64]])

(az/defstruct TeamRadioLog
  "One human-readable semantic team/driver exchange retained in native memory.
  Strategist entries also retain the exact English model observation and timing."
  {:layout :extern}
  [[:valid :bool]
   [:team :u8]
   [:source :u8]
   [:target :u8]
   [:code :u8]
   [:pit_state :u8]
   [:instruction :u8]
   [:reserved :u8]
   [:model_accepted :bool]
   [:model_action :u8]
   [:prompt_byte_count :u16]
   [:input_token_count :u16]
   [:best_token :u32]
   [:tick :u64]
   [:decision_revision :u64]
   [:latency_us :u64]
   [:tokens_per_second :f32]
   [:tire_condition :f32]
   [:damage :f32]
   [:prompt_bytes [:array 160 :u8]]])

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
   [:pending_urgent :bool]
   [:pending_target :u8]
   [:lane_target :f32]
   [:target_speed :f32]
   [:aggression :f32]
   [:patience :f32]
   [:risk :f32]
   [:next_decision_tick :u64]
   [:pending_revision :u64]
   [:pending_tick :u64]
   [:last_decision_tick :u64]
   [:decision_revision :u64]
   [:decisions :u64]
   [:urgent_decisions :u64]
   [:invalid_decisions :u64]
   [:deadline_misses :u64]
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
   [:checkpoint :u8]
   [:finished :bool]
   [:item :u8]
   [:shielded :bool]
   [:team :u8]
   [:teammate :u8]
   [:pit_state :u8]
   [:pit_stops :u8]
   [:damage_stage :u8]
   [:radio_code :u8]
   [:radio_source :u8]
   [:team_instruction :u8]
   [:team_pending :bool]
   [:source :u8]
   [:pending :bool]
   [:pending_urgent :bool]
   [:item_action :u8]
   [:tire_condition :f32]
   [:damage :f32]
   [:pit_seconds :f32]
   [:progress :f32]
   [:lane :f32]
   [:speed :f32]
   [:x :f32]
   [:y :f32]
   [:heading :f32]
   [:lane_target :f32]
   [:target_speed :f32]
   [:target :u8]
   [:pending_age_ticks :u64]
   [:intent_age_ticks :u64]
   [:decision_revision :u64]
   [:decisions :u64]
   [:deadline_misses :u64]
   [:last_latency_us :u64]
   [:average_latency_us :u64]
   [:radio_revision :u64]
   [:team_decision_revision :u64]
   [:team_decisions :u64]
   [:team_last_latency_us :u64]
   [:team_average_latency_us :u64]
   [:finish_tick :u64]])

(az/defstruct ObservationView
  "Exact bounded game-state observation offered to one racer decision. This is
  intentionally smaller than `RacerView`: opponents contribute only the
  selected target's relative distance/lane, never private world state."
  {:layout :extern}
  [[:valid :bool]
   [:racer :u8]
   [:rank :u8]
   [:target :u8]
   [:persona :u8]
   [:item :u8]
   [:target_distance :u8]
   [:target_lane :u8]
   [:tactical_status :u8]
   [:urgent :bool]
   [:lap :u16]
   [:progress :f32]
   [:speed :f32]])

(az/defstruct RaceSnapshot
  "Allocation-free monitoring summary of the live native race."
  {:layout :extern}
  [[:initialized :bool]
   [:paused :bool]
   [:state :u8]
   [:human_controlled :bool]
   [:countdown_ticks :u16]
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
   [:deadline_misses :u64]
   [:max_intent_age_ticks :u64]
   [:items_used :u64]
   [:hits :u64]
   [:contacts :u64]
   [:active_hazards :u8]
   [:hazards_spawned :u64]
   [:pit_stops :u64]
   [:team_radio_messages :u64]
   [:accidents :u64]
   [:team_ai_decisions :u64]
   [:world_address :usize]])

(az/defstruct HumanControlSnapshot
  "Inspectable optional reference-driver input. Twenty-AI mode remains the
  default; enabling this takes over racer 0 without changing any other racer."
  {:layout :extern}
  [[:enabled :bool]
   [:steering :f32]
   [:throttle :f32]
   [:brake :f32]
   [:use_item :bool]])

(az/defstruct CadenceSummary
  "Inspectable load-sensitive ordinary thought cadence. The standalone host
  may present live simulation in slow motion while replay remains 120 Hz."
  {:layout :extern}
  [[:ticks :u64]
   [:pending :u8]
   [:adaptations :u64]
   [:max_latency_us :u64]
   [:decisions_per_second :f32]])

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

(az/defstruct ReplayParityReport
  "Canonical gameplay-state comparison for one captured native intent stream."
  {:layout :extern}
  [[:valid :bool]
   [:intent_count :u16]
   [:ticks :u32]
   [:original_fingerprint :u64]
   [:replay_fingerprint :u64]])

(az/defstruct ReplayFileSummary
  "Strict result for loading one portable recorded-intent fixture."
  {:layout :extern}
  [[:valid :bool]
   [:error_code :u32]
   [:intent_count :u16]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:model_fingerprint :u64]
   [:action_head_fingerprint :u64]])

(az/defvar world [:optional [:* flecs/ecs_world_t]] null)

(az/defvar racer-component :u64 0)

(az/defvar brain-component :u64 0)

(az/defvar hazard-component :u64 0)

(az/defvar team-component :u64 0)

(az/defvar vehicle-component :u64 0)

(az/defvar dynamics-world b3/b3WorldId (std-mem/zeroes (az/type b3/b3WorldId)))

(az/defvar dynamics-surface [:optional [:* b3/b3MeshData]] ak/null)

(az/defvar dynamics-barriers [:optional [:* b3/b3MeshData]] ak/null)

(az/defvar vehicle-controls [:array racer-count driver/Control]
  (std-mem/zeroes (az/type [:array racer-count driver/Control])))

(az/defvar lane-plans [:array racer-count driver/LanePlan]
  (std-mem/zeroes (az/type [:array racer-count driver/LanePlan])))

(az/defvar lap-checkpoints [:array racer-count :u8]
  (std-mem/zeroes (az/type [:array racer-count :u8])))

(az/defvar entities [:array racer-count :u64] (std-mem/zeroes (az/type [:array racer-count :u64])))

(az/defvar hazard-entities [:array 32 :u64]
  (std-mem/zeroes (az/type [:array 32 :u64])))

(az/defvar team-entities [:array team-count :u64]
  (std-mem/zeroes (az/type [:array team-count :u64])))

(az/defvar initialized false)

(az/defvar paused false)

(az/defstruct LanguageDriverState
  "Opt-in ordinary-language control; no body ownership or pose cache."
  {:layout :extern}
  [[:enabled :bool] [:kind :u8] [:epoch :u64] [:revision :u64] [:expires_tick :u64]])

(az/defstruct LanguagePlanEvent
  "Exact accepted/rejected reply, retained independently of the old head log.
  Manual/fixture submissions explicitly have generated=false."
  {:layout :extern}
  [[:valid :bool] [:accepted :bool] [:generated :bool] [:racer :u8]
   [:reason :u8] [:plan protocol/DrivingPlan]
   [:sequence :u64] [:revision :u64] [:epoch :u64]
   [:observed_tick :u64] [:install_tick :u64] [:expires_tick :u64]
   [:byte_count :u16] [:truncated :bool] [:text [:array 2048 :u8]]])

(az/defvar language-drivers [:array racer-count LanguageDriverState]
  (std-mem/zeroes (az/type [:array racer-count LanguageDriverState])))

(az/defvar language-events [:array 128 LanguagePlanEvent]
  (std-mem/zeroes (az/type [:array 128 LanguagePlanEvent])))

(az/defvar language-event-sequence :u64 0)

(az/defstruct LanguageSchedule {:layout :extern}
  [[:revision :u64] [:next_tick :u64] [:marker :u8]])

(az/defvar language-schedules [:array racer-count LanguageSchedule]
  (std-mem/zeroes (az/type [:array racer-count LanguageSchedule])))

(az/defstruct LanguageExchange
  "Exact immutable model input/output and timings, plus installation outcome."
  {:layout :extern}
  [[:valid :bool] [:sequence :u64] [:reason :u8] [:result worker/LanguageResult]])

(az/defvar language-exchanges [:array 128 LanguageExchange]
  (std-mem/zeroes (az/type [:array 128 LanguageExchange])))

(az/defvar language-exchange-sequence :u64 0)

(az/defvar language-exchange-lock :u8 0)

(az/defvar language-mode-requests [:array racer-count :u8]
  (std-mem/zeroes (az/type [:array racer-count :u8])))

(az/defconst language-request-budget-ticks :u64 3600)

(az/defvar simulation-tick :u64 0)

(az/defvar item-use-count :u64 0)

(az/defvar hit-count :u64 0)

(az/defvar contact-count :u64 0)

(az/defvar contact-cooldowns [:array racer-count :u8]
  (std-mem/zeroes (az/type [:array racer-count :u8])))

(az/defvar hazard-spawn-count :u64 0)

(az/defvar pit-stop-count :u64 0)

(az/defvar team-radio-count :u64 0)

(az/defvar accident-count :u64 0)

(az/defvar team-ai-decision-count :u64 0)

(az/defvar team-radio-history [:array (* team-count team-radio-history-per-team) TeamRadioLog]
  (std-mem/zeroes (az/type [:array (* team-count team-radio-history-per-team) TeamRadioLog])))

(az/defvar team-radio-heads [:array team-count :u8]
  (std-mem/zeroes (az/type [:array team-count :u8])))

(az/defvar team-radio-counts [:array team-count :u8]
  (std-mem/zeroes (az/type [:array team-count :u8])))

(az/defvar race-epoch :u64 1)

(az/defvar race-seed :u64 0)

(az/defvar configured-countdown-ticks :u16 0)

(az/defvar countdown-ticks :u16 0)

(az/defvar race-state :u8 race-state-running)

(az/defvar human-controlled false)

(az/defvar human-steering :f32 0.0)

(az/defvar human-throttle :f32 0.0)

(az/defvar human-brake :f32 0.0)

(az/defvar human-use-item false)

(az/defvar items-enabled true)

(az/defvar decision-sequence :u64 0)

(az/defvar current-ordinary-thought-ticks :u64 ordinary-thought-ticks)

(az/defvar cadence-pending :u8 0)

(az/defvar cadence-max-latency-us :u64 0)

(az/defvar cadence-adaptations :u64 0)

(az/defvar replay-intents [:array 512 ReplayIntent]
  (std-mem/zeroes (az/type [:array 512 ReplayIntent])))

(az/defvar replay-count :usize 0)

(az/defvar replay-cursor :usize 0)

(az/defvar replay-active false)

(az/defn next-decision-revision!
  :-
  :u64
  []
  (do
    (set! decision-sequence (+ decision-sequence 1))
    decision-sequence))

(az/defn cadence-ticks-for-pressure
  "Pure bounded policy for ordinary thoughts. Urgent requests never use this
  backoff and simulation cadence is unaffected."
  :-
  :u64
  [[pending :u8]
   [max-latency-us :u64]]
  (cond
    (>= max-latency-us 333000) critical-thought-ticks
    (or (>= max-latency-us 250000) (>= pending 7)) overloaded-thought-ticks
    (>= pending 5) pressured-thought-ticks
    :else ordinary-thought-ticks))

(az/defn update-thought-cadence!
  :-
  :void
  []
  (let [workers (worker/summary)
        ^{:var true :zig/type :u64} max-latency-us 0]
    (when initialized
      (dotimes [index racer-count]
        (set! max-latency-us
              (ak/max max-latency-us
                      (az/field (az/deref (brain-pointer index))
                                average_latency_us)))))
    (let [pending (az/field workers pending)
          desired (cadence-ticks-for-pressure pending max-latency-us)]
      (when (ak/!= desired current-ordinary-thought-ticks)
        (set! cadence-adaptations (+ cadence-adaptations 1)))
      (set! current-ordinary-thought-ticks desired)
      (set! cadence-pending pending)
      (set! cadence-max-latency-us max-latency-us))))

(az/defn cadence-summary
  "Inspect ordinary AI cadence and the pressure that selected it."
  :-
  CadenceSummary
  []
  (CadenceSummary
   {:ticks current-ordinary-thought-ticks
    :pending cadence-pending
    :adaptations cadence-adaptations
    :max_latency_us cadence-max-latency-us
    :decisions_per_second
    (/ 120.0
       (ak/as :f32 (ak/floatFromInt current-ordinary-thought-ticks)))}))

(az/defn decision-deadline-ticks
  "Return the hard simulation-time budget for one ordinary or urgent thought."
  :-
  :u64
  [[urgent :bool]]
  (if urgent
    urgent-decision-deadline-ticks
    ordinary-decision-deadline-ticks))

(az/defn decision-expired?
  "Pure deadline predicate used by the scheduler and native regression tests."
  :-
  :bool
  [[enqueue-tick :u64]
   [current-tick :u64]
   [urgent :bool]]
  (and (>= current-tick enqueue-tick)
       (>= (- current-tick enqueue-tick)
           (decision-deadline-ticks urgent))))

(az/defn clear-replay!
  "Clear the loaded replay without modifying the current race."
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
  :-
  ReplaySummary
  []
  (ReplaySummary
   {:active replay-active
    :loaded (ak/intCast replay-count)
    :installed (ak/intCast replay-cursor)
    :remaining (ak/intCast (- replay-count replay-cursor))}))

(az/defn replay-intent-before
  :-
  :bool
  [[left ReplayIntent]
   [right ReplayIntent]]
  (or (< (az/field left install_tick) (az/field right install_tick))
      (and (ak/== (az/field left install_tick)
                  (az/field right install_tick))
           (or (< (az/field left racer) (az/field right racer))
               (and (ak/== (az/field left racer) (az/field right racer))
                    (< (az/field left revision)
                       (az/field right revision)))))))

(az/defn capture-retained-replay!
  "Capture every retained accepted decision entirely in native memory and
  order it by install tick, racer, and revision for deterministic playback."
  :-
  ReplaySummary
  []
  (clear-replay!)
  (dotimes [racer-index racer-count]
    (let [racer-id (ak/as :u8 (ak/intCast racer-index))
          count (ak/as :usize
                       (ak/intCast
                        (ak/min (telemetry/decision-count racer-id)
                                telemetry/entries-per-racer)))]
      (dotimes [offset count]
        (when (< replay-count replay-capacity)
          (let [entry (telemetry/entry-at racer-id offset)]
            (when (and (az/field entry valid)
                       (az/field entry accepted)
                       (ak/== (az/field entry observation_schema)
                              protocol/observation-schema-version)
                       (ak/== (az/field entry action_schema)
                              protocol/action-schema-version))
              (set! (az/index replay-intents replay-count)
                    (ReplayIntent
                     {:valid true :accepted true
                      :urgent (az/field entry urgent)
                      :racer (az/field entry racer_id)
                      :rank (az/field entry rank) :lap (az/field entry lap)
                      :item (az/field entry item) :action (az/field entry action)
                      :target (az/field entry target)
                      :observation_schema (az/field entry observation_schema)
                      :action_schema (az/field entry action_schema)
                      :reserved 0 :revision (az/field entry revision)
                      :install_tick (az/field entry install_tick)
                      :lane_target (az/field entry lane_target)
                      :target_speed (az/field entry target_speed)}))
              (set! replay-count (+ replay-count 1))))))))
  (let [^{:var true :zig/type :usize} index 1]
    (ak/while (< index replay-count)
      (let [key (az/index replay-intents index)
            ^{:var true :zig/type :usize} cursor index]
        (ak/while (and (> cursor 0)
                       (replay-intent-before
                        key (az/index replay-intents (- cursor 1))))
          (set! (az/index replay-intents cursor)
                (az/index replay-intents (- cursor 1)))
          (set! cursor (- cursor 1)))
        (set! (az/index replay-intents cursor) key))
      (set! index (+ index 1))))
  (replay-summary))

(az/defn replay-read-u16
  :-
  :u16
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as :u16 (az/index bytes offset))
     (ak/<< (ak/as :u16 (az/index bytes (+ offset 1))) 8)))

(az/defn replay-read-u32
  :-
  :u32
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as :u32 (az/index bytes offset))
     (ak/<< (ak/as :u32 (az/index bytes (+ offset 1))) 8)
     (ak/<< (ak/as :u32 (az/index bytes (+ offset 2))) 16)
     (ak/<< (ak/as :u32 (az/index bytes (+ offset 3))) 24)))

(az/defn replay-read-u64
  :-
  :u64
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as :u64 (replay-read-u32 bytes offset))
     (ak/<< (ak/as :u64 (replay-read-u32 bytes (+ offset 4))) 32)))

(az/defn replay-read-f32
  :-
  :f32
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (let [^{:zig/type :u32} bits (replay-read-u32 bytes offset)
        ^{:zig/type :f32} value (ak/bitCast bits)]
    value))

(az/defn replay-file-summary
  :-
  ReplayFileSummary
  [[valid :bool]
   [error-code :u32]
   [intent-count :u16]
   [observation-schema :u8]
   [action-schema :u8]
   [model-fingerprint :u64]
   [action-head-fingerprint :u64]]
  (ReplayFileSummary
   {:valid valid :error_code error-code :intent_count intent-count
    :observation_schema observation-schema :action_schema action-schema
    :model_fingerprint model-fingerprint
    :action_head_fingerprint action-head-fingerprint}))

(az/defn load-replay-file!
  "Load one canonical little-endian replay artifact with strict schema,
  provenance, size, ordering, and intent validation."
  :-
  ReplayFileSummary
  [[path [:pointer {:size :c :const? true} :u8]]]
  (let [file (runtime/fopen path "rb")]
    (if (ak/== file null)
      (replay-file-summary false replay-file-open-failed 0 0 0 0 0)
      (do
        (set! _ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (set! _ (runtime/fseek file 0 0))
          (if (or (< signed-size (ak/as :isize replay-file-header-bytes))
                  (> signed-size
                     (ak/as :isize
                            (+ replay-file-header-bytes
                               (* replay-capacity replay-file-entry-bytes)))))
            (do
              (set! _ (runtime/fclose file))
              (replay-file-summary false replay-file-invalid-size 0 0 0 0 0))
            (let [size (ak/as :usize (ak/intCast signed-size))
                  allocation (runtime/malloc size)]
              (if (ak/== allocation null)
                (do
                  (set! _ (runtime/fclose file))
                  (replay-file-summary false replay-file-invalid-size
                                       0 0 0 0 0))
                (let [bytes (az/cast allocation [:c-pointer :u8])
                      read-count (runtime/fread bytes 1 size file)
                      ^{:var true :zig/type ReplayFileSummary}
                      result
                      (replay-file-summary false replay-file-invalid-size
                                           0 0 0 0 0)]
                  (set! _ (runtime/fclose file))
                  (when (ak/== read-count size)
                    (let [magic-valid
                          (and (ak/== (az/index bytes 0) 65)
                               (ak/== (az/index bytes 1) 71)
                               (ak/== (az/index bytes 2) 82)
                               (ak/== (az/index bytes 3) 80)
                               (ak/== (az/index bytes 4) 76)
                               (ak/== (az/index bytes 5) 89)
                               (ak/== (az/index bytes 6) 48)
                               (ak/== (az/index bytes 7) 49))
                          version (replay-read-u16 bytes 8)
                          count (replay-read-u16 bytes 10)
                          observation-schema (az/index bytes 12)
                          action-schema (az/index bytes 13)
                          model-fingerprint (replay-read-u64 bytes 16)
                          action-head-fingerprint (replay-read-u64 bytes 24)
                          expected-size
                          (+ replay-file-header-bytes
                             (* (ak/as :usize count) replay-file-entry-bytes))
                          compatible
                          (and (ak/== (az/index bytes 14) racer-count)
                               (ak/== (az/index bytes 15) team-count)
                               (ak/== observation-schema
                                      protocol/observation-schema-version)
                               (ak/== action-schema
                                      protocol/action-schema-version)
                               (ak/== model-fingerprint
                                      protocol/model-fingerprint)
                               (ak/== action-head-fingerprint
                                      protocol/action-head-fingerprint))]
                      (cond
                        (or (ak/! magic-valid)
                            (ak/!= version replay-file-version))
                        (set! result
                              (replay-file-summary
                               false replay-file-invalid-header count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        (or (ak/== count 0)
                            (> count replay-capacity)
                            (ak/!= size expected-size))
                        (set! result
                              (replay-file-summary
                               false replay-file-invalid-size count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        (ak/! compatible)
                        (set! result
                              (replay-file-summary
                               false replay-file-incompatible count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        :else
                        (do
                          (clear-replay!)
                          (let [^{:var true :zig/type :bool} all-valid true]
                            (dotimes [index (ak/as :usize count)]
                              (when all-valid
                                (let [base (+ replay-file-header-bytes
                                              (* index replay-file-entry-bytes))
                                      intent
                                      (ReplayIntent
                                       {:valid true :accepted true
                                        :racer (az/index bytes base)
                                        :rank (az/index bytes (+ base 1))
                                        :lap (replay-read-u16 bytes (+ base 2))
                                        :item (az/index bytes (+ base 4))
                                        :action (az/index bytes (+ base 5))
                                        :target (az/index bytes (+ base 6))
                                        :urgent (ak/!= (az/index bytes (+ base 7)) 0)
                                        :observation_schema observation-schema
                                        :action_schema action-schema :reserved 0
                                        :revision (replay-read-u64 bytes (+ base 8))
                                        :install_tick
                                        (replay-read-u64 bytes (+ base 16))
                                        :lane_target
                                        (replay-read-f32 bytes (+ base 24))
                                        :target_speed
                                        (replay-read-f32 bytes (+ base 28))})]
                                  (when (ak/! (append-replay-intent! intent))
                                    (set! all-valid false)))))
                            (if all-valid
                              (set! result
                                    (replay-file-summary
                                     true replay-file-ok count
                                     observation-schema action-schema
                                     model-fingerprint action-head-fingerprint))
                              (do
                                (clear-replay!)
                                (set! result
                                      (replay-file-summary
                                       false replay-file-invalid-intent count
                                       observation-schema action-schema
                                       model-fingerprint
                                       action-head-fingerprint)))))))))
                  (runtime/free allocation)
                  result)))))))))

(az/defn register-component
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
  :-
  [:* Racer]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               racer-component (ak/sizeOf Racer))
      (az/cast [:* Racer])))

(az/defn brain-pointer
  :-
  [:* RacerBrain]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               brain-component (ak/sizeOf RacerBrain))
      (az/cast [:* RacerBrain])))

(az/defn team-pointer
  :-
  [:* Team]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index team-entities index)
                               team-component (ak/sizeOf Team))
      (az/cast [:* Team])))

(az/defvar status-component :u64 0)

(az/defvar recovery-component :u64 0)

(az/defvar turnaround-component :u64 0)

(az/defn turnaround-pointer :- [:* turnaround/Output] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               turnaround-component (ak/sizeOf turnaround/Output))
      (az/cast [:* turnaround/Output])))

(az/defn ensure-turnaround!
  "Attach independent sparse turnaround state on the frame thread. Existing
  chassis, wheel and driver allocations remain untouched."
  :- :void []
  (when (ak/== turnaround-component 0)
    (set! turnaround-component
          (register-component (az/unwrap world) "RacingTurnaround"
                              (ak/sizeOf turnaround/Output) (ak/alignOf turnaround/Output)))
    (flecs/ecs_add_id world turnaround-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [value (std-mem/zeroes (az/type turnaround/Output))]
        (flecs/ecs_set_id world (az/index entities i) turnaround-component
                          (ak/sizeOf turnaround/Output) (ak/& value))))))

(az/defn turnaround-view
  "Inspect native body-relative recovery without changing driver intent."
  :- turnaround/Output [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= turnaround-component 0))
    (az/deref (turnaround-pointer index))
    (std-mem/zeroes (az/type turnaround/Output))))

(az/defvar lap-timing-component :u64 0)

(az/defn lap-timing-pointer :- [:* lap-timing/Entry] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               lap-timing-component (ak/sizeOf lap-timing/Entry))
      (az/cast [:* lap-timing/Entry])))

(az/defn ensure-lap-timing!
  "Attach timing on the frame thread without recreating live vehicles."
  :- :void []
  (when (ak/== lap-timing-component 0)
    (set! lap-timing-component
          (register-component (az/unwrap world) "RacingLapTiming"
                              (ak/sizeOf lap-timing/Entry) (ak/alignOf lap-timing/Entry)))
    (flecs/ecs_add_id world lap-timing-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [^:var value (std-mem/zeroes (az/type lap-timing/Entry))]
        (set! (az/field value complete_start)
              (or (ak/== simulation-tick 0) (> countdown-ticks 0)))
        (flecs/ecs_set_id world (az/index entities i) lap-timing-component
                          (ak/sizeOf lap-timing/Entry) (ak/& value))))))

(az/defn lap-timing-view
  "Measured 120Hz ticks, with an explicit flag for a mid-lap live attachment."
  :- lap-timing/Entry [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= lap-timing-component 0))
    (az/deref (lap-timing-pointer index))
    (std-mem/zeroes (az/type lap-timing/Entry))))

(az/defn recovery-pointer :- [:* recovery/Output] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               recovery-component (ak/sizeOf recovery/Output))
      (az/cast [:* recovery/Output])))

(az/defn ensure-recovery!
  "Frame-thread addition of independent sparse manoeuvring state. Does not
  recreate vehicles or move the running world's existing component storage."
  :- :void []
  (when (ak/== recovery-component 0)
    (set! recovery-component
          (register-component (az/unwrap world) "RacingRecovery"
                              (ak/sizeOf recovery/Output) (ak/alignOf recovery/Output)))
    (flecs/ecs_add_id world recovery-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [value (std-mem/zeroes (az/type recovery/Output))]
        (flecs/ecs_set_id world (az/index entities i) recovery-component
                          (ak/sizeOf recovery/Output) (ak/& value))))))

(az/defn recovery-view
  "Inspect actual manoeuvring state, gear and pedals without altering the race."
  :- recovery/Output [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= recovery-component 0))
    (az/deref (recovery-pointer index))
    (std-mem/zeroes (az/type recovery/Output))))

(az/defn status-pointer :- [:* status/Entry] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               status-component (ak/sizeOf status/Entry))
      (az/cast [:* status/Entry])))

(az/defn ensure-race-status!
  "Add a new sparse component to the existing world; ordinary reload keeps it."
  :- :void []
  (when (ak/== status-component 0)
    (set! status-component
          (register-component (az/unwrap world) "RacingClassification"
                              (ak/sizeOf status/Entry) (ak/alignOf status/Entry)))
    (flecs/ecs_add_id world status-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [entry (std-mem/zeroes (az/type status/Entry))]
        (flecs/ecs_set_id world (az/index entities i) status-component
                          (ak/sizeOf status/Entry) (ak/& entry))))))

(az/defn retired?
  "DNF is not finished: no lap or finish tick is manufactured."
  :- :bool [[index :usize]]
  (and (< index racer-count) (ak/!= status-component 0)
       (az/field (az/deref (status-pointer index)) retired)))

(az/defn retirement-view
  "Inspect retirement reason, frozen classification and its actual race tick."
  :- status/Entry [[index :usize]]
  (if (and (< index racer-count) (ak/!= status-component 0))
    (az/deref (status-pointer index))
    (std-mem/zeroes (az/type status/Entry))))

(az/defn retired-count :- :u8 []
  (let [^{:var :u8} count 0]
    (dotimes [i racer-count]
      (when (retired? i) (set! count (+ count 1))))
    count))

(az/defn teammate-id
  "Return the other driver in one of the four fixed two-driver teams."
  :-
  :u8
  [[identifier :u8]]
  (if (ak/== (mod identifier 2) 0)
    (+ identifier 1)
    (- identifier 1)))

(az/defn radio-message!
  "Publish one bounded semantic message and retain it in the team's native
  newest-first history. Source identifies driver or strategist direction."
  :-
  :void
  [[index :usize]
   [source :u8]
   [code :u8]]
  (let [racer (racer-pointer index)
        team-index (ak/as :usize
                          (ak/intCast (az/field (az/deref racer) team)))
        team (team-pointer team-index)
        head (ak/as :usize
                    (ak/intCast (az/index team-radio-heads team-index)))
        destination (+ (* team-index team-radio-history-per-team) head)]
    (set! (az/field (az/deref team) radio_sequence)
          (+ (az/field (az/deref team) radio_sequence) 1))
    (set! (az/field (az/deref team) radio_code) code)
    (set! (az/field (az/deref team) radio_target)
          (az/field (az/deref racer) id))
    (set! (az/field (az/deref racer) radio_code) code)
    (set! (az/field (az/deref racer) radio_source) source)
    (set! (az/field (az/deref racer) radio_revision)
          (az/field (az/deref team) radio_sequence))
    (set! (az/index team-radio-history destination)
          (TeamRadioLog
           {:valid true
            :team (az/field (az/deref team) id)
            :source source
            :target (az/field (az/deref racer) id)
            :code code
            :pit_state (az/field (az/deref racer) pit_state)
            :instruction (az/field (az/deref team) instruction)
            :reserved 0
            :model_accepted false
            :model_action 0
            :prompt_byte_count 0
            :input_token_count 0
            :best_token 0
            :tick simulation-tick
            :decision_revision (az/field (az/deref team) decision_revision)
            :latency_us (az/field (az/deref team) last_latency_us)
            :tokens_per_second 0.0
            :tire_condition (az/field (az/deref racer) tire_condition)
            :damage (az/field (az/deref racer) damage)
            :prompt_bytes
            (std-mem/zeroes (az/type [:array 160 :u8]))}))
    (set! (az/index team-radio-heads team-index)
          (ak/intCast (mod (+ head 1) team-radio-history-per-team)))
    (set! (az/index team-radio-counts team-index)
          (ak/intCast
           (ak/min team-radio-history-per-team
                   (+ (ak/as :usize
                             (ak/intCast
                              (az/index team-radio-counts team-index)))
                      1))))
    (set! team-radio-count (+ team-radio-count 1))))

(az/defn attach-team-model-decision!
  "Attach the completed strategist inference to the radio message it caused."
  :-
  :void
  [[team-index :usize]
   [result worker/InferenceResult]]
  (let [head (ak/as :usize
                    (ak/intCast (az/index team-radio-heads team-index)))
        slot (if (ak/== head 0)
               (- team-radio-history-per-team 1)
               (- head 1))
        destination (+ (* team-index team-radio-history-per-team) slot)
        ^:var entry (az/index team-radio-history destination)
        prompt-count
        (ak/min worker/prompt-capacity
                (ak/as :usize
                       (ak/intCast (az/field result prompt_byte_count))))]
    (when (and (az/field entry valid)
               (ak/== (az/field entry source) radio-source-strategist)
               (ak/== (az/field entry decision_revision)
                      (az/field result revision)))
      (set! (az/field entry model_accepted) (az/field result accepted))
      (set! (az/field entry model_action) (az/field result action_code))
      (set! (az/field entry prompt_byte_count) (ak/intCast prompt-count))
      (set! (az/field entry input_token_count)
            (az/field result input_token_count))
      (set! (az/field entry best_token) (az/field result best_token))
      (set! (az/field entry tokens_per_second)
            (az/field result tokens_per_second))
      (dotimes [position prompt-count]
        (set! (az/index (az/field entry prompt_bytes) position)
              (az/index (az/field result prompt_bytes) position)))
      (set! (az/index team-radio-history destination) entry))))

(az/defn team-radio-history-count
  "Return the retained radio-message count for one team."
  :-
  :u8
  [[team-id :u8]]
  (if (< team-id team-count)
    (az/index team-radio-counts (ak/intCast team-id))
    0))

(az/defn team-radio-entry
  "Inspect one newest-first semantic radio exchange for one team."
  :-
  TeamRadioLog
  [[team-id :u8]
   [offset :usize]]
  (if (or (>= team-id team-count)
          (>= offset
              (ak/as :usize
                     (ak/intCast
                      (az/index team-radio-counts (ak/intCast team-id))))))
    (std-mem/zeroes (az/type TeamRadioLog))
    (let [team-index (ak/as :usize (ak/intCast team-id))
          head (ak/as :usize
                      (ak/intCast (az/index team-radio-heads team-index)))
          distance (+ offset 1)
          slot (if (>= head distance)
                 (- head distance)
                 (- (+ team-radio-history-per-team head) distance))]
      (az/index team-radio-history
                (+ (* team-index team-radio-history-per-team) slot)))))

(az/defn team-view
  "Inspect one of the four Flecs-owned teams."
  :-
  TeamView
  [[identifier :u8]]
  (if (or (ak/! initialized) (>= identifier team-count))
    (TeamView {:valid false :id identifier :driver_a 0 :driver_b 0
               :pit_occupant no-pit-occupant :instruction team-action-hold
               :radio_code radio-none :radio_target 0 :pending false
               :pit_stops 0 :decision_revision 0 :decisions 0
               :invalid_decisions 0 :last_latency_us 0 :average_latency_us 0
               :radio_sequence 0})
    (let [team (team-pointer (ak/intCast identifier))]
      (TeamView
       {:valid true
        :id identifier
        :driver_a (az/field (az/deref team) driver_a)
        :driver_b (az/field (az/deref team) driver_b)
        :pit_occupant (az/field (az/deref team) pit_occupant)
        :instruction (az/field (az/deref team) instruction)
        :radio_code (az/field (az/deref team) radio_code)
        :radio_target (az/field (az/deref team) radio_target)
        :pending (az/field (az/deref team) pending)
        :pit_stops (az/field (az/deref team) pit_stops)
        :decision_revision (az/field (az/deref team) decision_revision)
        :decisions (az/field (az/deref team) decisions)
        :invalid_decisions (az/field (az/deref team) invalid_decisions)
        :last_latency_us (az/field (az/deref team) last_latency_us)
        :average_latency_us (az/field (az/deref team) average_latency_us)
        :radio_sequence (az/field (az/deref team) radio_sequence)}))))

(az/defn install-replay-intents!
  "Install every recorded intent due on this exact fixed simulation tick."
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
      (set! (az/field (az/deref brain) pending_urgent) false)
      (set! (az/field (az/deref brain) pending_target)
            (az/field intent racer))
      (set! (az/field (az/deref brain) pending_revision) 0)
      (set! (az/field (az/deref brain) pending_tick) 0)
      (set! (az/field (az/deref brain) last_decision_tick) simulation-tick)
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
         :tokenizer_version protocol/tokenizer-version
         :quantization_version protocol/quantization-version
         :quantization_format protocol/quantization-format
         :action_head_training_revision
         protocol/action-head-training-revision
         :training_data_fingerprint protocol/training-data-fingerprint
         :training_data_sha256 protocol/training-data-sha256
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
  :-
  [:* Hazard]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index hazard-entities index)
                               hazard-component (ak/sizeOf Hazard))
      (az/cast [:* Hazard])))

(az/defn wrapped-distance
  :-
  :f32
  [[a :f32]
   [b :f32]]
  (let [distance (ak/abs (- a b))]
    (ak/min distance (- 1.0 distance))))

(az/defn racers-overlap?
  "Pure circle/contact predicate in track coordinates. Absolute progress keeps
  racers on adjacent laps physically close across the finish line while never
  colliding racers separated by a full lap."
  :-
  :bool
  [[absolute-progress-a :f32]
   [lane-a :f32]
   [absolute-progress-b :f32]
   [lane-b :f32]]
  (and (< (ak/abs (- absolute-progress-a absolute-progress-b)) 0.018)
       (< (ak/abs (- lane-a lane-b)) 0.058)))

(az/defn contact-axis-overlap?
  "Separating-axis test for a 5.8m long, 2.6m wide car, in km world units."
  :- :bool [[dx :f32] [dy :f32] [axis :f32] [heading-a :f32] [heading-b :f32]]
  (let [distance (ak/abs (+ (* dx (std-math/cos axis)) (* dy (std-math/sin axis))))
        a (- heading-a axis) b (- heading-b axis)
        radius-a (+ (* 0.0029 (ak/abs (std-math/cos a)))
                    (* 0.0013 (ak/abs (std-math/sin a))))
        radius-b (+ (* 0.0029 (ak/abs (std-math/cos b)))
                    (* 0.0013 (ak/abs (std-math/sin b))))]
    (< distance (+ radius-a radius-b 0.0001))))

(az/defn racer-contours-overlap?
  "Oriented physical car bounds, not the old screen-space circle radius."
  :- :bool [[a [:* Racer]] [b [:* Racer]]]
  (let [dx (- (az/field (az/deref a) x) (az/field (az/deref b) x))
        dy (- (az/field (az/deref a) y) (az/field (az/deref b) y))
        ha (az/field (az/deref a) heading) hb (az/field (az/deref b) heading)]
    (and (contact-axis-overlap? dx dy ha ha hb)
         (contact-axis-overlap? dx dy (+ ha 1.5707963) ha hb)
         (contact-axis-overlap? dx dy hb ha hb)
         (contact-axis-overlap? dx dy (+ hb 1.5707963) ha hb))))

(az/defn hit-racer!
  "Apply visible counterplay and immediately schedule a fresh thought for the
  struck racer. Shields absorb exactly one hit."
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
                        (track/progress-step (az/field (az/deref hazard) speed) fixed-step))
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
  :-
  :void
  [[racer [:* Racer]]]
  (let [progress (az/field (az/deref racer) progress)
        lane (az/field (az/deref racer) lane)
        state (az/field (az/deref racer) pit_state)
        sample (if (or (and (ak/== state pit-state-called) (>= progress pit-entry-progress))
                       (ak/== state pit-state-servicing) (ak/== state pit-state-exiting))
                 (track/pit-pose progress lane)
                 (track/pose progress lane))]
    (set! (az/field (az/deref racer) x) (az/field sample x))
    (set! (az/field (az/deref racer) y) (az/field sample y))
    (set! (az/field (az/deref racer) heading) (az/field sample heading))))

(az/defn absolute-progress
  :-
  :f32
  [[racer [:* Racer]]]
  (+ (ak/as :f32 (ak/floatFromInt (az/field (az/deref racer) lap)))
     (az/field (az/deref racer) progress)))

(az/defn checkpoint-for-progress
  "Return the last legal quarter-lap checkpoint crossed, from 0 through 3.
  Progress is authoritative and can only advance, so checkpoint order cannot
  be skipped by steering or by a malformed external control value."
  :-
  :u8
  [[progress :f32]]
  (cond
    (>= progress 0.75) 3
    (>= progress 0.50) 2
    (>= progress 0.25) 1
    :else 0))

(az/defn complete-lap!
  "Advance exactly one lap and permanently record this racer's finish tick."
  :-
  :void
  [[racer [:* Racer]]]
  (set! (az/field (az/deref racer) lap)
        (+ (az/field (az/deref racer) lap) 1))
  (when (and (ak/! (az/field (az/deref racer) finished))
             (>= (az/field (az/deref racer) lap) lap-count))
    (set! (az/field (az/deref racer) finished) true)
    (set! (az/field (az/deref racer) finish_tick) simulation-tick)))

(az/defn advance-racer-progress!
  "Advance along the legal track direction and cross the finish line at most
  once. Negative or invalid reverse movement cannot manufacture a lap."
  :-
  :void
  [[racer [:* Racer]]
   [distance :f32]]
  (let [advanced (+ (az/field (az/deref racer) progress)
                    (ak/max distance 0.0))]
    (if (>= advanced 1.0)
      (do
        (set! (az/field (az/deref racer) progress) (- advanced 1.0))
        (complete-lap! racer))
      (set! (az/field (az/deref racer) progress) advanced))))

(az/defn choose-target
  "Nearest physical car ahead, independent of lap/classification. A lapped,
  finished or retired body still occupies the track and must remain visible
  to the model. This does not schedule decisions for a retired driver."
  :-
  :u8
  [[self-index :usize]]
  (let [self (racer-pointer self-index)
        ^{:var true :zig/type :u8} chosen (az/field (az/deref self) id)
        ^{:var true :zig/type :f32} best-distance 1000.0]
    (dotimes [other-index racer-count]
      (when (ak/!= other-index self-index)
        (let [other (racer-pointer other-index)
              distance (mod (- (az/field (az/deref other) progress)
                               (az/field (az/deref self) progress)) 1.0)]
          (when (and (> distance 0.0)
                     (< distance best-distance))
            (set! best-distance distance)
            (set! chosen (az/field (az/deref other) id))))))
    chosen))

(az/defn target-distance-bin
  "Quantize only the selected visible opponent's forward distance. Nine means
  no opponent ahead; lower values are progressively closer."
  :-
  :u8
  [[self-index :usize]
   [target :u8]]
  (let [self (racer-pointer self-index)]
    (if (ak/== target (az/field (az/deref self) id))
      9
      (let [other (racer-pointer (ak/as :usize (ak/intCast target)))
            distance
            (mod (- (az/field (az/deref other) progress)
                    (az/field (az/deref self) progress)) 1.0)]
        (ak/as :u8
               (ak/intFromFloat (ak/min 9.0 (* distance 100.0))))))))

(az/defn target-lane-relation
  "Describe the selected opponent as left, same-lane, or right of the racer."
  :-
  :u8
  [[self-index :usize]
   [target :u8]]
  (let [self (racer-pointer self-index)]
    (if (ak/== target (az/field (az/deref self) id))
      target-lane-same
      (let [other (racer-pointer (ak/as :usize (ak/intCast target)))
            delta (- (az/field (az/deref other) lane)
                     (az/field (az/deref self) lane))]
        (cond
          (< delta -0.020) target-lane-left
          (> delta 0.020) target-lane-right
          :else target-lane-same)))))

(az/defn classify-tactical-status
  "Summarize immediate control constraints before passive protection. A shield
  does not make a blocked lane clear; a stunned driver must first recover."
  :- :u8
  [[stunned :bool] [hazard-near :bool] [shielded :bool]]
  (cond
    stunned tactical-status-stunned
    hazard-near tactical-status-hazard
    shielded tactical-status-shielded
    :else tactical-status-clear))

(az/defn racer-tactical-status
  "Expose local hazards, including stopped/wrecked cars in the driven lane.
  Physical obstacles do not disappear when their driver is classified DNF."
  :-
  :u8
  [[index :usize]]
  (let [racer (racer-pointer index)
        ^{:var true :zig/type :bool} hazard-near false]
    (dotimes [slot hazard-capacity]
      (let [hazard (hazard-pointer slot)]
        (when (and (az/field (az/deref hazard) active)
                   (ak/!= (ak/as :usize
                                 (ak/intCast
                                  (az/field (az/deref hazard) owner)))
                          index)
                   (< (wrapped-distance
                       (az/field (az/deref hazard) progress)
                       (az/field (az/deref racer) progress))
                      0.060)
                   (< (ak/abs (- (az/field (az/deref hazard) lane)
                                 (az/field (az/deref racer) lane)))
                      0.055))
          (set! hazard-near true))))
    (dotimes [other-index racer-count]
      (when (ak/!= index other-index)
        (let [other (racer-pointer other-index)
              ahead (mod (- (az/field (az/deref other) progress)
                             (az/field (az/deref racer) progress)) 1.0)]
          (when (and (> ahead 0.0) (< ahead 0.02)
                     (< (az/field (az/deref other) speed) 0.002)
                     (< (ak/abs (- (az/field (az/deref other) lane)
                                   (az/field (az/deref racer) lane))) 0.055))
            (set! hazard-near true)))))
    (classify-tactical-status
      (> (az/field (az/deref racer) stun_seconds) 0.0)
      hazard-near
      (az/field (az/deref racer) shielded))))

(az/defn build-observation
  "Build the single authoritative immutable worker observation."
  :-
  ObservationView
  [[index :usize]
   [urgent :bool]]
  (if (or (ak/! initialized) (>= index racer-count))
    (std-mem/zeroes (az/type ObservationView))
    (let [racer (racer-pointer index)
          brain (brain-pointer index)
          aggression (az/field (az/deref brain) aggression)
          persona (cond
                    (< aggression 0.48) (ak/as :u8 0)
                    (< aggression 0.72) (ak/as :u8 1)
                    :else (ak/as :u8 2))
          target (choose-target index)]
      (ObservationView
       {:valid true
        :racer (az/field (az/deref racer) id)
        :rank (az/field (az/deref racer) rank)
        :target target
        :persona persona
        :item (az/field (az/deref racer) item)
        :target_distance (target-distance-bin index target)
        :target_lane (target-lane-relation index target)
        :tactical_status (racer-tactical-status index)
        :urgent urgent
        :lap (az/field (az/deref racer) lap)
        :progress (az/field (az/deref racer) progress)
        :speed (az/field (az/deref racer) speed)}))))

(az/defn current-observation
  "Inspect exactly what the next native decision for one racer can see."
  :-
  ObservationView
  [[index :usize]]
  (if (or (ak/! initialized) (>= index racer-count))
    (std-mem/zeroes (az/type ObservationView))
    (build-observation index
                       (az/field (az/deref (brain-pointer index)) urgent))))

(az/defn make-decision!
  "Install one independent tactical intent. This transparent policy is the
  native fallback and training baseline used whenever LLM output is late."
  :-
  :void
  [[index :usize]
   [urgent :bool]
   [deadline-status :u8]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        aggression (az/field (az/deref brain) aggression)
        risk (az/field (az/deref brain) risk)
        ;; Publication revisions remain monotonic across hot resets. Tactical
        ;; behavior must use reset-local state so the same seed reproduces the
        ;; same physical race independently of prior REPL activity.
        lane-phase (mod (+ (az/field (az/deref brain) decisions)
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
    (set! (az/field (az/deref brain) source) telemetry/source-fallback)
    ;; Urgency is an edge-triggered request. Consuming the decision clears it;
    ;; a later native event can set it again without turning every thought into
    ;; an urgent one.
    (set! (az/field (az/deref brain) urgent) false)
    (set! (az/field (az/deref brain) pending) false)
    (set! (az/field (az/deref brain) pending_urgent) false)
    (set! (az/field (az/deref brain) pending_target)
          (az/field (az/deref racer) id))
    (set! (az/field (az/deref brain) pending_revision) 0)
    (set! (az/field (az/deref brain) pending_tick) 0)
    (set! (az/field (az/deref brain) decision_revision) revision)
    (set! (az/field (az/deref brain) last_decision_tick) simulation-tick)
    (set! (az/field (az/deref brain) decisions)
          (+ (az/field (az/deref brain) decisions) 1))
    (when urgent
      (set! (az/field (az/deref brain) urgent_decisions)
            (+ (az/field (az/deref brain) urgent_decisions) 1)))
    (set! (az/field (az/deref brain) next_decision_tick)
          (+ simulation-tick current-ordinary-thought-ticks))
    (telemetry/record-fallback!
     (az/field (az/deref racer) id)
     (az/field (az/deref racer) rank)
     (az/field (az/deref racer) lap)
     (az/field (az/deref racer) item)
     target
     (az/field (az/deref brain) item_action)
     urgent
     deadline-status
     revision
     race-epoch
     simulation-tick
     (az/field (az/deref racer) progress)
     (az/field (az/deref racer) speed)
     lane-target
     target-speed)))

(az/defn record-worker-result!
  :-
  :void
  [[result worker/InferenceResult]
   [accepted :bool]
   [item-action :u8]
   [deadline-status :u8]]
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
          :validation_code (if (> deadline-status 0)
                             2
                             (if accepted 0 1))
          :deadline_status deadline-status
          :lap (az/field result lap)
          :input_token_count (az/field result input_token_count)
          :output_token_count (az/field result output_token_count)
          :prompt_byte_count (az/field result prompt_byte_count)
          :response_byte_count 1
          :tokenizer_version protocol/tokenizer-version
          :quantization_version protocol/quantization-version
          :quantization_format protocol/quantization-format
          :action_head_training_revision
          protocol/action-head-training-revision
          :training_data_fingerprint protocol/training-data-fingerprint
          :training_data_sha256 protocol/training-data-sha256
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

(az/defn valid-worker-action?
  "Validate the complete constrained action envelope before it can affect the
  live race. The target must be the exact bounded opponent selected in the
  immutable observation, not merely an in-range racer id."
  :-
  :bool
  [[accepted :bool]
   [action-code :u8]
   [item-action :u8]
   [target :u8]
   [expected-target :u8]
   [lane-target :f32]
   [target-speed :f32]
   [output-token-count :u16]
   [best-token :u32]]
  (and accepted
       (< action-code 8)
       (or (ak/== item-action action-hold)
           (ak/== item-action action-use))
       (< target racer-count)
       (ak/== target expected-target)
       (>= lane-target -0.075)
       (<= lane-target 0.075)
       (>= target-speed 0.04)
       (<= target-speed 0.12)
       (ak/== output-token-count 1)
       (ak/== best-token (+ 32 action-code))))

(az/defn install-worker-result!
  "Install only an on-time result for the current race epoch and outstanding
  revision. Late results remain observable but never replace the safe intent."
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
               (az/field (az/deref brain) pending)
               (ak/== (az/field result revision)
                      (az/field (az/deref brain) pending_revision)))
      (let [expired
            (decision-expired? (az/field result simulation_tick)
                               simulation-tick
                               (az/field result urgent))
            semantically-accepted
            (valid-worker-action?
             (az/field result accepted)
             (az/field result action_code)
             (az/field result item_action)
             (az/field result target)
             (az/field (az/deref brain) pending_target)
             (az/field result lane_target)
             (az/field result target_speed)
             (az/field result output_token_count)
             (az/field result best_token))
            accepted (and semantically-accepted (ak/! expired))
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
          (set! (az/field (az/deref brain) source) telemetry/source-llm)
          (set! (az/field (az/deref brain) last_decision_tick)
                simulation-tick))
        (when (and (ak/! semantically-accepted) (ak/! expired))
          (set! (az/field (az/deref brain) invalid_decisions)
                (+ (az/field (az/deref brain) invalid_decisions) 1)))
        (when expired
          (set! (az/field (az/deref brain) deadline_misses)
                (+ (az/field (az/deref brain) deadline_misses) 1))
          (set! (az/field (az/deref brain) urgent) true))
        (set! (az/field (az/deref brain) pending) false)
        (set! (az/field (az/deref brain) pending_urgent) false)
        (set! (az/field (az/deref brain) pending_target)
              (az/field (az/deref racer) id))
        (set! (az/field (az/deref brain) pending_revision) 0)
        (set! (az/field (az/deref brain) pending_tick) 0)
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
              (if (or (ak/! accepted)
                      (az/field (az/deref brain) urgent))
                simulation-tick
                (+ (az/field result simulation_tick)
                   current-ordinary-thought-ticks)))
        (record-worker-result! result accepted item-action
                               (if expired
                                 deadline-expired
                                 deadline-on-time))))))

(az/defn submit-worker-request!
  :-
  :bool
  [[index :usize]
   [urgent :bool]]
  (let [brain (brain-pointer index)
        revision (next-decision-revision!)
        observation (build-observation index urgent)
        request
        (worker/InferenceRequest
         {:valid true
          :actor_kind worker/actor-kind-driver
          :team (az/field (az/deref (racer-pointer index)) team)
          :racer (az/field observation racer)
          :rank (az/field observation rank)
          :lap (az/field observation lap)
          :item (az/field observation item)
          :target (az/field observation target)
          :persona (az/field observation persona)
          :target_distance (az/field observation target_distance)
          :target_lane (az/field observation target_lane)
          :tactical_status (az/field observation tactical_status)
          :driver_a 0 :driver_b 0 :rank_a 0 :rank_b 0
          :tire_a 0 :tire_b 0 :damage_a 0 :damage_b 0
          :pit_a 0 :pit_b 0 :box_occupied false
          :urgent (az/field observation urgent)
          :observation_schema protocol/observation-schema-version
          :action_schema protocol/action-schema-version
          :revision revision
          :race_epoch race-epoch
          :simulation_tick simulation-tick
          :progress (az/field observation progress)
          :speed (az/field observation speed)
          :enqueue_seconds 0.0})]
    (if (worker/submit! request)
      (do
        (set! (az/field (az/deref brain) pending) true)
        (set! (az/field (az/deref brain) pending_urgent) urgent)
        (set! (az/field (az/deref brain) pending_target)
              (az/field observation target))
        (set! (az/field (az/deref brain) pending_revision) revision)
        (set! (az/field (az/deref brain) pending_tick) simulation-tick)
        ;; Only consume the urgency captured by this immutable request. A new
        ;; hit or pickup can set the edge again while inference is in flight.
        (set! (az/field (az/deref brain) urgent) false)
        (set! (az/field (az/deref brain) next_decision_tick)
              (+ simulation-tick current-ordinary-thought-ticks))
        true)
      false)))

(az/defn apply-item!
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
          (set! (az/field (az/deref racer) boost_seconds) 3.0)

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
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)]
    (when (and items-enabled
               (ak/== (az/field (az/deref racer) item) item-none)
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

(az/defn apply-human-control!
  "Translate the optional reference driver's normalized input into the same
  bounded RacerBrain intent fields consumed by the native vehicle controller."
  :-
  :void
  []
  (let [brain (brain-pointer 0)
        steering (ak/min 1.0 (ak/max -1.0 human-steering))
        throttle (ak/min 1.0 (ak/max 0.0 human-throttle))
        brake (ak/min 1.0 (ak/max 0.0 human-brake))
        lane-target
        (ak/min 0.075
                (ak/max -0.075
                        (+ (az/field (az/deref brain) lane_target)
                           (* steering fixed-step 0.72))))
        target-speed
        (ak/min 0.12
                (ak/max 0.015
                        (- (+ 0.020 (* throttle 0.100))
                           (* brake 0.080))))]
    (set! (az/field (az/deref brain) lane_target) lane-target)
    (set! (az/field (az/deref brain) target_speed) target-speed)
    (set! (az/field (az/deref brain) target) (choose-target 0))
    (set! (az/field (az/deref brain) item_action)
          (if human-use-item action-use action-hold))
    (set! (az/field (az/deref brain) source) telemetry/source-human)
    (set! (az/field (az/deref brain) pending) false)
    (set! (az/field (az/deref brain) pending_urgent) false)
    (set! (az/field (az/deref brain) pending_target) 0)
    (set! (az/field (az/deref brain) pending_revision) 0)
    (set! (az/field (az/deref brain) pending_tick) 0)
    (set! (az/field (az/deref brain) last_decision_tick) simulation-tick)
    (set! (az/field (az/deref brain) urgent) false)))

(az/defn expire-pending-decision!
  "Replace an over-budget in-flight thought with a new deterministic safe
  intent. The worker may finish later, but its older revision cannot install."
  :-
  :bool
  [[index :usize]]
  (let [brain (brain-pointer index)
        expired
        (and (az/field (az/deref brain) pending)
             (decision-expired?
              (az/field (az/deref brain) pending_tick)
              simulation-tick
              (az/field (az/deref brain) pending_urgent)))]
    (when expired
      (set! (az/field (az/deref brain) deadline_misses)
            (+ (az/field (az/deref brain) deadline_misses) 1))
      (make-decision! index true deadline-expired))
    expired))

(az/defn update-tire-strategy!
  "Consume tire-contact work and report the first warning to the strategist.
  Waiting and unexecuted lane intentions cause no wear; a pit call does not
  suspend wear while driving to the box. Pit selection belongs to the team AI."
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        loss (physics/take-vehicle-tread-loss! (az/deref (vehicle-pointer index)))]
    (when (and (ak/!= (az/field (az/deref racer) pit_state)
                       pit-state-servicing)
               (ak/! (az/field (az/deref racer) finished)))
      (set! (az/field (az/deref racer) tire_condition)
            (ak/max 0.0 (- (az/field (az/deref racer) tire_condition) loss)))
      (when (and (ak/== (az/field (az/deref racer) tire_stage) 0)
                 (<= (az/field (az/deref racer) tire_condition)
                     tire-warning-threshold))
        (set! (az/field (az/deref racer) tire_stage) 1)
        (radio-message! index radio-source-driver radio-tires-wearing)
        (set! (az/field (az/deref brain) urgent) true)
        (set! (az/field (az/deref brain) next_decision_tick)
              simulation-tick)))))

(az/defn driver-needs-pit?
  "Return whether current tires or persistent collision damage justify a
  strategist pit call. Finished or already-called cars are not candidates."
  :-
  :bool
  [[racer [:* Racer]]]
  (and (ak/! (az/field (az/deref racer) finished))
       (ak/! (retired? (az/field (az/deref racer) id)))
       (ak/== (az/field (az/deref racer) pit_state) pit-state-track)
       (or (<= (az/field (az/deref racer) tire_condition)
               tire-pit-threshold)
           (>= (az/field (az/deref racer) damage)
               damage-pit-threshold))))

(az/defn driver-service-urgency
  :-
  :f32
  [[racer [:* Racer]]]
  (+ (- 1.0 (az/field (az/deref racer) tire_condition))
     (* 1.35 (az/field (az/deref racer) damage))))

(az/defn team-priority-driver
  "Choose the teammate with the strongest current service need for the safety
  fallback and for the strategist's bounded observation target."
  :-
  :u8
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a (az/field (az/deref team) driver_a)
        driver-b (az/field (az/deref team) driver_b)
        racer-a (racer-pointer (ak/intCast driver-a))
        racer-b (racer-pointer (ak/intCast driver-b))
        needs-a (driver-needs-pit? racer-a)
        needs-b (driver-needs-pit? racer-b)]
    (cond
      (and needs-a (ak/! needs-b)) driver-a
      (and needs-b (ak/! needs-a)) driver-b
      (> (driver-service-urgency racer-b)
         (driver-service-urgency racer-a)) driver-b
      :else driver-a)))

(az/defn request-driver-pit!
  "Reserve one team's real pit box and publish the strategist's instruction."
  :-
  :bool
  [[team-index :usize]
   [driver-id :u8]
   [voluntary :bool]]
  (let [team (team-pointer team-index)
        racer (racer-pointer (ak/intCast driver-id))
        brain (brain-pointer (ak/intCast driver-id))
        free (ak/== (az/field (az/deref team) pit_occupant)
                    no-pit-occupant)
        candidate (and (ak/! (az/field (az/deref racer) finished))
                       (ak/! (retired? driver-id))
                       (ak/== (az/field (az/deref racer) pit_state) pit-state-track)
                       (or voluntary (ak/! (az/field (az/index language-drivers driver-id) enabled)))
                       (or voluntary (driver-needs-pit? racer)))]
    (if (and free candidate)
      (do
        (set! (az/field (az/deref team) pit_occupant) driver-id)
        (set! (az/field (az/deref team) instruction)
              (if (ak/== driver-id (az/field (az/deref team) driver_a))
                team-action-driver-a
                team-action-driver-b))
        (set! (az/field (az/deref racer) pit_state) pit-state-called)
        (set! (az/field (az/deref racer) pit_waiting) false)
        (set! (az/field (az/deref racer) tire_stage) 2)
        (radio-message!
         (ak/intCast driver-id) radio-source-strategist
         (if (>= (az/field (az/deref racer) damage) damage-pit-threshold)
           radio-repair-confirmed
           radio-pit-confirmed))
        (set! (az/field (az/deref brain) urgent) true)
        (set! (az/field (az/deref brain) next_decision_tick) simulation-tick)
        true)
      (do
        (when (and candidate
                   (ak/!= (az/field (az/deref team) pit_occupant) driver-id)
                   (ak/! (az/field (az/deref racer) pit_waiting)))
          (set! (az/field (az/deref racer) pit_waiting) true)
          (radio-message! (ak/intCast driver-id) radio-source-strategist
                          radio-box-occupied))
        false))))

(az/defn call-driver-to-pit!
  "Existing automatic tire/damage policy; voluntary language calls use the
  same physical pit workflow with an explicit optional-service request."
  :- :bool [[team-index :usize] [driver-id :u8]]
  (request-driver-pit! team-index driver-id false))

(az/defn install-team-worker-result!
  "Validate and install one team strategist's independent LLM decision. The
  model chooses hold/driver A/driver B; current box ownership and damage/tire
  state remain authoritative safety constraints."
  :-
  :void
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        actor-index (+ team-worker-offset team-index)
        result (worker/result-for actor-index
                                  (az/field (az/deref team) decision_revision))]
    (when (and (az/field (az/deref team) pending)
               (az/field result valid)
               (ak/== (az/field result revision)
                      (az/field (az/deref team) pending_revision))
               (ak/== (az/field result race_epoch) race-epoch))
      (let [model-action (az/field result action_code)
            model-driver
            (cond
              (ak/== model-action team-action-driver-a)
              (az/field (az/deref team) driver_a)

              (ak/== model-action team-action-driver-b)
              (az/field (az/deref team) driver_b)

              :else no-pit-occupant)
            priority (team-priority-driver team-index)
            priority-racer (racer-pointer (ak/intCast priority))
            emergency (or (< (az/field (az/deref priority-racer) tire_condition)
                             0.16)
                          (> (az/field (az/deref priority-racer) damage) 0.82))
            model-valid (and (az/field result accepted)
                             (ak/== (az/field result actor_kind)
                                    worker/actor-kind-team)
                             (ak/== (az/field result team) team-index)
                             (< (az/field result action_code) 3)
                             (ak/== (az/field result output_token_count) 1)
                             (ak/== (az/field result best_token)
                                    (+ 32 (az/field result action_code))))
            selected
            (if (and model-valid
                     (ak/!= model-driver no-pit-occupant)
                     (driver-needs-pit?
                      (racer-pointer (ak/intCast model-driver))))
              model-driver
              (if emergency priority no-pit-occupant))]
        (set! (az/field (az/deref team) pending) false)
        (set! (az/field (az/deref team) pending_revision) 0)
        (set! (az/field (az/deref team) decision_revision)
              (az/field result revision))
        (set! (az/field (az/deref team) decisions)
              (+ (az/field (az/deref team) decisions) 1))
        (set! team-ai-decision-count (+ team-ai-decision-count 1))
        (set! (az/field (az/deref team) last_latency_us)
              (az/field result total_us))
        (set! (az/field (az/deref team) average_latency_us)
              (if (ak/== (az/field (az/deref team) decisions) 1)
                (az/field result total_us)
                (/ (+ (az/field (az/deref team) average_latency_us)
                      (az/field result total_us))
                   2)))
        (when (ak/! model-valid)
          (set! (az/field (az/deref team) invalid_decisions)
                (+ (az/field (az/deref team) invalid_decisions) 1)))
        (if (ak/!= selected no-pit-occupant)
          (set! _ (call-driver-to-pit! team-index selected))
          (let [target (team-priority-driver team-index)]
            (set! (az/field (az/deref team) instruction) team-action-hold)
            (radio-message! (ak/intCast target) radio-source-strategist
                            radio-stay-out)))
        (attach-team-model-decision! team-index result)
        (set! (az/field (az/deref team) next_decision_tick)
              (+ simulation-tick team-decision-cadence-ticks))))))

(az/defn submit-team-worker-request!
  :-
  :bool
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a-id (az/field (az/deref team) driver_a)
        driver-b-id (az/field (az/deref team) driver_b)
        driver-a (racer-pointer (ak/intCast driver-a-id))
        driver-b (racer-pointer (ak/intCast driver-b-id))
        revision (next-decision-revision!)
        actor-index (+ team-worker-offset team-index)
        tire-a
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (az/field (az/deref driver-a)
                                             tire_condition))))))
        tire-b
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (az/field (az/deref driver-b)
                                             tire_condition))))))
        damage-a
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (az/field (az/deref driver-a) damage))))))
        damage-b
        (ak/as :u8
               (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (az/field (az/deref driver-b) damage))))))
        request
        (worker/InferenceRequest
         {:valid true
          :actor_kind worker/actor-kind-team
          :team (ak/intCast team-index)
          :racer (ak/intCast actor-index)
          :rank (az/field (az/deref driver-a) rank)
          :lap (az/field (az/deref driver-a) lap)
          :item (if (ak/== (az/field (az/deref team) pit_occupant)
                           no-pit-occupant) 0 1)
          :target driver-a-id
          :persona 0 :target_distance 0 :target_lane 1 :tactical_status 0
          :driver_a driver-a-id
          :driver_b driver-b-id
          :rank_a (az/field (az/deref driver-a) rank)
          :rank_b (az/field (az/deref driver-b) rank)
          :tire_a tire-a :tire_b tire-b
          :damage_a damage-a :damage_b damage-b
          :pit_a (if (retired? driver-a-id) 4 (az/field (az/deref driver-a) pit_state))
          :pit_b (if (retired? driver-b-id) 4 (az/field (az/deref driver-b) pit_state))
          :box_occupied
          (ak/!= (az/field (az/deref team) pit_occupant) no-pit-occupant)
          :urgent true
          :observation_schema protocol/observation-schema-version
          :action_schema protocol/action-schema-version
          :revision revision
          :race_epoch race-epoch
          :simulation_tick simulation-tick
          :progress (az/field (az/deref driver-a) progress)
          :speed (az/field (az/deref driver-a) speed)
          :enqueue_seconds 0.0})]
    (if (worker/submit! request)
      (do
        (set! (az/field (az/deref team) pending) true)
        (set! (az/field (az/deref team) pending_revision) revision)
        (set! (az/field (az/deref team) pending_tick) simulation-tick)
        true)
      false)))

(az/defn step-team-strategist!
  "Advance one independent team AI actor after both drivers have updated."
  :-
  :void
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a (racer-pointer
                  (ak/intCast (az/field (az/deref team) driver_a)))
        driver-b (racer-pointer
                  (ak/intCast (az/field (az/deref team) driver_b)))
        needs-decision (or (driver-needs-pit? driver-a)
                           (driver-needs-pit? driver-b))]
    (install-team-worker-result! team-index)
    (when (and needs-decision
               (ak/! (az/field (az/deref team) pending))
               (ak/== (az/field (az/deref team) pit_occupant)
                      no-pit-occupant)
               (>= simulation-tick
                   (az/field (az/deref team) next_decision_tick)))
      (when (ak/! (submit-team-worker-request! team-index))
        (let [priority (team-priority-driver team-index)]
          (set! _ (call-driver-to-pit! team-index priority))
          (set! (az/field (az/deref team) next_decision_tick)
                (+ simulation-tick team-decision-cadence-ticks)))))))

(az/defn pit-box-progress
  :-
  :f32
  [[team-id :u8]]
  ;; Ten 12.9m-spaced boxes remain within the fully widened pit apron.
  (+ 0.968 (* (ak/as :f32 (ak/floatFromInt team-id)) 0.003)))

(az/defn vehicle-pointer :- [:* physics/Vehicle] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (az/index entities index)
                               vehicle-component (ak/sizeOf physics/Vehicle))
      (az/cast [:* physics/Vehicle])))

(az/defn ensure-dynamics!
  "Frame-thread ownership. Ordinary reload preserves the world and sparse bodies."
  :- :void []
  (when (ak/== dynamics-surface ak/null)
    (set! lane-plans (std-mem/zeroes (az/type [:array racer-count driver/LanePlan])))
    (set! dynamics-world (physics/create-world -9.81))
    (set! dynamics-surface (terrain/create! dynamics-world))
    (set! vehicle-component
          (register-component (az/unwrap world) "RacingPhysicalVehicle"
                              (ak/sizeOf physics/Vehicle) (ak/alignOf physics/Vehicle)))
    (flecs/ecs_add_id world vehicle-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [racer (racer-pointer i)
            progress (az/field (az/deref racer) progress)
            p (circuit/at-distance (* progress 4309.0)
                                   (* (az/field (az/deref racer) lane) 50.0))
            car (physics/create-vehicle dynamics-world
                  (b3/b3Pos {:x (az/field p x) :y (az/field p y)
                             :z (+ (az/field p z) 0.76)})
                  (az/field p heading))
            ^{:zig/type [:optional [:* :anyopaque]]} tag (ak/ptrFromInt (+ i 1))]
        (b3/b3Body_SetUserData (az/field car chassis) tag)
        (dotimes [wheel 4]
          (b3/b3Body_SetUserData (az/index (az/field car wheels) wheel) tag))
        (flecs/ecs_set_id world (az/index entities i) vehicle-component
                          (ak/sizeOf physics/Vehicle) (ak/& car))
        (set! (az/index lap-checkpoints i) (checkpoint-for-progress progress)))))
  ;; Added once on the frame thread, including to an already-running world.
  ;; Existing vehicles and the road mesh are not recreated or repositioned.
  (when (ak/== dynamics-barriers ak/null)
    (set! dynamics-barriers (barriers/create! dynamics-world))))

(az/defn destroy-dynamics! :- :void []
  (when (ak/!= dynamics-surface ak/null)
    (physics/destroy-world! dynamics-world)
    (when (ak/!= dynamics-barriers ak/null)
      (barriers/destroy! (az/unwrap dynamics-barriers))
      (set! dynamics-barriers ak/null))
    (terrain/destroy! (az/unwrap dynamics-surface))
    (set! dynamics-surface ak/null)))

(az/defn vehicle-pose
  "Actual metre-space rigid pose: part 0 chassis, parts 1..4 individual wheels."
  :- physics/BodyState [[index :usize] [part :usize]]
  (let [car (az/deref (vehicle-pointer index))]
    (physics/body-state (if (ak/== part 0) (az/field car chassis)
                           (az/index (az/field car wheels) (- part 1))))))

(az/defn enable-language-driving!
  "Opt into readable plans on the simulation thread. Start held, invalidate
  pending encoded decisions, and preserve every physical body and race result."
  :- :bool [[index :usize] [enabled :bool]]
  (when (or (>= index racer-count) (ak/! initialized)) (ak/return false))
  (let [brain (brain-pointer index)]
    (set! (az/index language-drivers index)
      (LanguageDriverState {:enabled enabled :kind protocol/plan-hold
                            :epoch race-epoch
                            :revision (az/field (az/index language-drivers index) revision)
                            :expires_tick 0}))
    (set! (az/field (az/deref brain) pending) false)
    (set! (az/field (az/deref brain) target_speed) 0.0)
    (set! (az/field (az/deref brain) item_action) action-hold))
  true)

(az/defn language-lane-clear?
  "Read-only, short-horizon clearance of a proposed lateral transition.
  Positive offset is LEFT of the road heading. Sampled prediction is not a
  collision guarantee: continuous traffic braking and Box3D remain active."
  :- :bool [[index :usize] [lane-metres :f32]]
  (let [body (vehicle-pose index 0)
        projected (track/project (* (az/field body x) 0.001) (* (az/field body y) 0.001))
        speed (ak/sqrt (+ (* (az/field body vx) (az/field body vx))
                          (* (az/field body vy) (az/field body vy))))
        distance (ak/max 8.0 (* speed 1.5))]
    (dotimes [sample-index 16]
      (let [fraction (/ (ak/as :f32 (ak/floatFromInt (+ sample-index 1))) 16.0)
            seconds (* fraction 1.5)
            blend (* fraction fraction (- 3.0 (* 2.0 fraction)))
            lane (+ (* 50.0 (az/field projected lane) (- 1.0 blend)) (* lane-metres blend))
            point (circuit/at-distance
                    (+ (* (az/field projected progress) 4309.0) (* distance fraction)) lane)
            x (az/field point x) y (az/field point y) yaw (az/field point heading)]
        (when (ak/! (turnaround/static-clearance dynamics-world x y
                       (+ (az/field point z) 0.76) yaw)) (ak/return false))
        (dotimes [other-index racer-count]
          (when (ak/!= other-index index)
            (let [^:var other (vehicle-pose other-index 0)
                  before (turnaround/footprint-separation (az/field body x)
                           (az/field body y) (turnaround/heading body) other)]
              (set! (az/field other x) (+ (az/field other x) (* (az/field other vx) seconds)))
              (set! (az/field other y) (+ (az/field other y) (* (az/field other vy) seconds)))
              (when (ak/! (turnaround/separation-safe? before
                             (turnaround/footprint-separation x y yaw other)))
                (ak/return false))))))))
  true)

(az/defn driving-observation
  "Read ONLY on the simulation owner thread. Uses current Box3D poses and
  physical route queries, not the old coarse/head observation or cached UI."
  :- protocol/DrivingObservation [[index :usize]]
  (when (or (>= index racer-count) (ak/! initialized))
    (ak/return (std-mem/zeroes (az/type protocol/DrivingObservation))))
  (let [body (vehicle-pose index 0)
        racer (az/deref (racer-pointer index))
        projected (track/project (* (az/field body x) 0.001) (* (az/field body y) 0.001))
        lane (* 50.0 (az/field projected lane))
        route (circuit/at-distance (* 4309.0 (az/field projected progress)) 0.0)
        team (az/deref (team-pointer (ak/intCast (az/field racer team))))
        active (and (ak/== (az/field racer pit_state) pit-state-track)
                    (ak/! (retired? index)) (ak/! (az/field racer finished))
                    (ak/== race-state race-state-running))]
    (protocol/DrivingObservation
      {:valid true :racer (ak/intCast index)
       :speed_kmh (* 3.6 (ak/sqrt (+ (* (az/field body vx) (az/field body vx))
                                     (* (az/field body vy) (az/field body vy)))))
       :off_track (> (ak/abs lane) 6.5)
       :wrong_way (< (std-math/cos (- (turnaround/heading body) (az/field route heading))) 0.0)
       :overturned (driver/overturned? body)
       :ahead_clear (language-lane-clear? index lane)
       :left_clear (language-lane-clear? index 3.75)
       :right_clear (language-lane-clear? index -3.75)
       :tire_percent (* 100.0 (az/field racer tire_condition))
       :damage_percent (* 100.0 (az/field racer damage))
       :pit_available (and active (ak/== (az/field racer pit_state) pit-state-track)
                           (ak/== (az/field team pit_occupant) no-pit-occupant))
       :active active})))

(az/defn language-driving-request
  "Build the exact bounded text handed to inference and retained in history.
  Invalid or overflowing observations are rejected, never silently clipped."
  :- worker/LanguageRequest [[index :usize] [revision :u64]]
  (let [observation (driving-observation index)
        ^:var request (std-mem/zeroes (az/type worker/LanguageRequest))
        system "Choose: hold (stop), follow, pass left, pass right, pit, yield (slow). Avoid blocked routes. Reply with one command. Optional Radio: your message."
        length (protocol/describe-driving-observation observation
                 (ak/& (az/index (az/field request prompt_bytes) 0)) 160)]
    (when (or (ak/! (az/field observation active)) (ak/== revision 0) (ak/== length 0))
      (ak/return request))
    (set! (az/field request valid) true)
    (set! (az/field request actor) (ak/intCast index))
    (set! (az/field request revision) revision)
    (set! (az/field request epoch) race-epoch)
    (set! (az/field request observed_tick) simulation-tick)
    (set! (az/field request expires_tick) (+ simulation-tick language-request-budget-ticks))
    (set! (az/field request prompt_byte_count) (ak/intCast length))
    (set! (az/field request system_byte_count) (ak/intCast (az/field system len)))
    (dotimes [i (az/field system len)]
      (set! (az/index (az/field request system_bytes) i) (az/index system i)))
    request))

(az/defn install-language-plan!
  "Simulation-thread handoff, never a worker-thread body mutation. Envelope
  identity/time comes from the request, not text invented by the model.
  Every bounded reply is retained verbatim, including rejected replies."
  :- :u8
  [[index :usize] [epoch :u64] [revision :u64] [observed :u64] [expires :u64]
   [bytes [:pointer {:size :c :const? true} :u8]] [length :usize] [complete :bool] [generated :bool]]
  (when (or (>= index racer-count) (ak/! initialized))
    (ak/return protocol/plan-inactive-driver))
  (let [state (ak/& (az/index language-drivers index))
        racer (racer-pointer index) brain (brain-pointer index)
        team-index (ak/as :usize (ak/intCast (az/field (az/deref racer) team)))
        team (team-pointer team-index)
        plan (protocol/parse-driving-plan bytes length complete)
        kind (az/field plan kind)
        on-track (ak/== (az/field (az/deref racer) pit_state) pit-state-track)
        context (protocol/DrivingPlanContext
                  {:epoch race-epoch :tick simulation-tick
                   :latest_revision (az/field (az/deref state) revision)
                   :active (and on-track (az/field (az/deref state) enabled)
                                (ak/! (retired? index)) (ak/! (az/field (az/deref racer) finished))
                                (ak/! replay-active) (ak/== race-state race-state-running))
                   :left_clear (or (ak/!= kind protocol/plan-pass-left) (language-lane-clear? index 3.75))
                   :right_clear (or (ak/!= kind protocol/plan-pass-right) (language-lane-clear? index -3.75))
                   ;; Race-control flags are still a separate open task; do not
                   ;; fabricate flag observations in the language interface.
                   :red_flag false :overtaking_allowed on-track
                   :pit_available (and on-track (ak/== (az/field (az/deref team) pit_occupant) no-pit-occupant))})
        ^:var reason (protocol/validate-driving-plan plan epoch revision observed expires context)]
    (when (and (ak/== reason protocol/plan-ok) (ak/== kind protocol/plan-pit)
               (ak/! (request-driver-pit! team-index (ak/intCast index) true)))
      (set! reason protocol/plan-pit-unavailable))
    (when (ak/== reason protocol/plan-ok)
      (set! (az/deref state)
        (LanguageDriverState {:enabled true :kind kind :epoch epoch :revision revision :expires_tick expires}))
      (set! (az/field (az/deref brain) source)
        (if generated telemetry/source-llm telemetry/source-human))
      (set! (az/field (az/deref brain) lane_target)
        (cond (ak/== kind protocol/plan-pass-left) 0.075
              (ak/== kind protocol/plan-pass-right) -0.075
              :else (az/field (az/deref racer) lane)))
      (set! (az/field (az/deref brain) target_speed)
        (cond (ak/== kind protocol/plan-hold) 0.0
              (ak/== kind protocol/plan-yield) 0.025
              :else 0.080)))
    (set! language-event-sequence (+ language-event-sequence 1))
    (let [event (ak/& (az/index language-events (mod language-event-sequence 128)))
          stored (ak/min length 2048)]
      (set! (az/deref event)
        (LanguagePlanEvent {:valid true :accepted (ak/== reason protocol/plan-ok)
          :generated generated :racer (ak/intCast index) :reason reason :plan plan
          :sequence language-event-sequence :revision revision :epoch epoch
          :observed_tick observed :install_tick simulation-tick :expires_tick expires
          :byte_count (ak/intCast stored) :truncated (> length stored)
          :text (std-mem/zeroes (az/type [:array 2048 :u8]))}))
      (dotimes [i stored] (set! (az/index (az/field (az/deref event) text) i) (az/index bytes i))))
    reason))

(az/defn language-event-at
  "A bounded history lookup; valid=false means absent or already overwritten."
  :- LanguagePlanEvent [[sequence :u64]]
  (let [event (az/index language-events (mod sequence 128))]
    (if (and (> sequence 0) (ak/== (az/field event sequence) sequence)) event
      (std-mem/zeroes (az/type LanguagePlanEvent)))))

(az/defn language-exchange-count :- :u64 []
  (ak/atomicLoad :u64 (ak/& language-exchange-sequence) :.acquire))

(az/defn language-exchange-at
  "Nonblocking, coherent copy for nREPL/UI. valid=false on a busy/absent slot;
  refresh to retry. No data race with the simulation's history writer."
  :- LanguageExchange [[sequence :u64]]
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& language-exchange-lock) 0 1 :.acq_rel :.acquire) ak/null)
    (ak/return (std-mem/zeroes (az/type LanguageExchange))))
  (ak/defer (ak/atomicStore :u8 (ak/& language-exchange-lock) 0 :.release))
  (let [entry (az/index language-exchanges (mod sequence 128))]
    (if (and (> sequence 0) (az/field entry valid) (ak/== (az/field entry sequence) sequence)) entry
      (std-mem/zeroes (az/type LanguageExchange)))))

(az/defn request-language-mode!
  "Thread-safe nREPL/UI command. The next native tick performs the change;
  the calling thread never reads or mutates Flecs/Box3D objects."
  :- :bool [[index :usize] [enabled :bool]]
  (when (>= index racer-count) (ak/return false))
  (ak/atomicStore :u8 (ak/& (az/index language-mode-requests index))
                  (if enabled (ak/as :u8 1) 2) :.release)
  true)

(az/defn step-language-driving!
  "Simulation-owned async handoff: no model execution or waiting here.
  Wake at 16 circuit markers, on a five-second incident recheck, or intent
  expiry. Existing valid intent controls the car while the worker thinks.
  This is driver scheduling; natural-language TEAM scheduling is still separate."
  :- :void []
  (dotimes [index racer-count]
    (let [mode (ak/atomicRmw :u8 (ak/& (az/index language-mode-requests index)) :.Xchg 0 :.acq_rel)
          schedule (ak/& (az/index language-schedules index))]
      (when (> mode 0)
        (set! _ (enable-language-driving! index (ak/== mode 1)))
        ;; A mode toggle invalidates a reply that was already in flight.
        (set! (az/field (az/index language-drivers index) revision)
              (ak/max (az/field (az/index language-drivers index) revision)
                      (az/field (az/deref schedule) revision)))
        (set! (az/field (az/deref schedule) next_tick) simulation-tick)
        (set! (az/field (az/deref schedule) marker) 255))
      ;; If a reader is copying history, leave the worker result in its mailbox
      ;; until the next tick. Neither block physics nor drop an unread reply.
      (when (and (ak/== (worker/language-mailbox-state index) 3)
                 (ak/== (ak/cmpxchgStrong :u8 (ak/& language-exchange-lock) 0 1 :.acq_rel :.acquire) ak/null))
        (ak/defer (ak/atomicStore :u8 (ak/& language-exchange-lock) 0 :.release))
        (let [reply (worker/take-language-result! index)]
        (when (az/field reply valid)
          (let [request (az/field reply request) generation (az/field reply generation)
                reason (install-language-plan! index (az/field request epoch)
                         (az/field request revision) (az/field request observed_tick)
                         (az/field request expires_tick)
                         (ak/& (az/index (az/field generation bytes) 0)) (az/field generation byte_count)
                         (and (az/field generation valid) (ak/== (az/field generation stop) 1)) true)]
            (let [sequence (+ (language-exchange-count) 1)]
              (set! (az/index language-exchanges (mod sequence 128))
                (LanguageExchange {:valid true :sequence sequence :reason reason :result reply}))
              (ak/atomicStore :u64 (ak/& language-exchange-sequence) sequence :.release))))))
      (let [state (az/index language-drivers index) racer (az/deref (racer-pointer index))
            marker (ak/as :u8 (ak/intFromFloat (* 16.0 (track/wrap-progress (az/field racer progress)))))
            incident (or (< (az/field racer speed) 0.002) (> (ak/abs (az/field racer lane)) 0.13))]
        (when (and (az/field state enabled) (ak/! paused) (ak/! replay-active)
                   (ak/== race-state race-state-running) (ak/! (retired? index))
                   (ak/! (az/field racer finished))
                   (>= simulation-tick (az/field (az/deref schedule) next_tick))
                   (ak/== (worker/language-mailbox-state index) 0)
                   (or (ak/!= marker (az/field (az/deref schedule) marker)) incident
                       (>= simulation-tick (az/field state expires_tick))))
          (let [revision (+ (ak/max (az/field (az/deref schedule) revision) (az/field state revision)) 1)
                request (language-driving-request index revision)]
            ;; Back off invalid/busy submissions too, rather than rebuilding
            ;; physical clearance and text 120 times per second.
            (set! (az/field (az/deref schedule) next_tick) (+ simulation-tick 600))
            (when (worker/submit-language! request)
              (set! (az/field (az/deref schedule) revision) revision)
              (set! (az/field (az/deref schedule) marker) marker))))))))

(az/defn enforce-language-holds!
  "Final pedal gate AFTER automatic recovery. Hold/expiry brakes the physical
  car; it never changes velocity, pose, progress, or freezes the physics world."
  :- :void []
  (dotimes [i racer-count]
    (let [state (az/index language-drivers i)]
      (when (and (az/field state enabled)
                 (ak/== (az/field (az/deref (racer-pointer i)) pit_state) pit-state-track)
                 (or (ak/== (az/field state kind) protocol/plan-hold)
                     (ak/!= (az/field state epoch) race-epoch)
                     (>= simulation-tick (az/field state expires_tick))))
        (set! (az/field (az/index vehicle-controls i) throttle) 0.0)
        (set! (az/field (az/index vehicle-controls i) brake) 1.0)))))

(az/defn physical-contacts!
  "Damage comes from Box3D impacts, never from positional separation patches."
  :- :void []
  (let [events (b3/b3World_GetContactEvents dynamics-world)]
    (dotimes [event-index (ak/as :usize (ak/intCast (az/field events hitCount)))]
      (let [hit (az/index (az/field events hitEvents) event-index)
            impact-speed (az/field hit approachSpeed)]
        (when (> impact-speed 3.0)
          (dotimes [side 2]
            (let [shape (if (ak/== side 0) (az/field hit shapeIdA) (az/field hit shapeIdB))
                  body (b3/b3Shape_GetBody shape)
                  tag (ak/intFromPtr (b3/b3Body_GetUserData body))]
              (when (and (> tag 0) (<= tag racer-count))
                (let [index (- tag 1) racer (racer-pointer index) brain (brain-pointer index)
                      other-shape (if (ak/== side 0) (az/field hit shapeIdB) (az/field hit shapeIdA))
                      other-tag (ak/intFromPtr (b3/b3Body_GetUserData (b3/b3Shape_GetBody other-shape)))
                      chassis (az/field (az/deref (vehicle-pointer index)) chassis)
                      rolling-contact (and (ak/== other-tag 0)
                                           (ak/!= (b3/b3StoreBodyId body) (b3/b3StoreBodyId chassis))
                                           (> (ak/abs (az/field (az/field hit normal) z)) 0.5))]
                  ;; A rotating polygonal tire can emit hit events against the
                  ;; road. Those are tire/road support, not chassis accidents.
                  (when (and (ak/! rolling-contact) (ak/! (retired? index))
                             (ak/== (az/index contact-cooldowns index) 0))
                    (set! (az/field (az/deref racer) damage)
                          (ak/min 1.0 (+ (az/field (az/deref racer) damage)
                                         (* (- impact-speed 3.0) 0.012))))
                    (set! (az/index contact-cooldowns index) 60)
                    (set! contact-count (+ contact-count 1))
                    (set! accident-count (+ accident-count 1))
                    (set! (az/field (az/deref brain) urgent) true)
                    (set! (az/field (az/deref brain) next_decision_tick) simulation-tick)
                    (radio-message! index radio-source-driver radio-car-damaged)))))))))))

(az/defn brake-for-traffic!
  "Measured occupied-lane braking before the solver. Does not choose overtakes
  for the AI, separate overlapping bodies, or override a human driver's input."
  :- :void []
  (dotimes [i racer-count]
    (when (ak/! (and human-controlled (ak/== i 0)))
      (let [racer (racer-pointer i)]
        (dotimes [j racer-count]
          (when (ak/!= i j)
            (let [front (racer-pointer j)
                  gap (* 4309.0 (mod (- (az/field (az/deref front) progress)
                                       (az/field (az/deref racer) progress)) 1.0))
                  lateral (* 50.0 (ak/abs (- (az/field (az/deref front) lane)
                                             (az/field (az/deref racer) lane))))]
              (when (and (< gap 500.0) (< lateral 2.7))
                (let [body (vehicle-pose j 0)
                      route (circuit/at-distance
                             (* 4309.0 (az/field (az/deref front) progress)) 0.0)
                      forward (ak/max 0.0
                                (+ (* (az/field body vx) (std-math/cos (az/field route heading)))
                                   (* (az/field body vy) (std-math/sin (az/field route heading)))))]
                  (set! (az/index vehicle-controls i)
                        (driver/yield-to-offset-obstacle
                          (az/index vehicle-controls i) gap forward
                          (* 50.0 (- (az/field (az/deref front) lane)
                                      (az/field (az/deref racer) lane))))))))))))))

(az/defn update-retirements!
  "Race-control classification from sustained physical evidence. Never moves,
  deletes or uprights a body, and never credits a retired car with a finish."
  :- :void []
  (when (ak/== race-state race-state-running)
    (dotimes [i racer-count]
      (let [entry (status-pointer i)
            previous (az/deref entry)
            racer (racer-pointer i)
            body (vehicle-pose i 0)
            up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                               (* (az/field body qy) (az/field body qy)))))
            speed (ak/sqrt (+ (* (az/field body vx) (az/field body vx))
                             (* (az/field body vy) (az/field body vy))
                             (* (az/field body vz) (az/field body vz))))
            next (status/observe-world-position previous up speed
                                 (az/field body z) circuit/minimum-elevation simulation-tick
                                 (az/field (az/deref racer) lap)
                                 (az/field (az/deref racer) progress)
                                 (az/field (az/deref racer) finished))]
        (set! (az/deref entry) next)
        (when (and (az/field next retired) (ak/! (az/field previous retired)))
          (let [brain (brain-pointer i)
                team (team-pointer (az/field (az/deref racer) team))]
            (set! (az/field (az/deref brain) pending) false)
            (set! (az/field (az/deref brain) pending_revision) 0)
            (set! (az/field (az/deref brain) urgent) false)
            (set! (az/field (az/deref racer) boost_seconds) 0.0)
            (when (ak/== (az/field (az/deref team) pit_occupant) i)
              (set! (az/field (az/deref team) pit_occupant) no-pit-occupant))
            (radio-message! i radio-source-race-control
                            (if (ak/== (az/field next reason) status/reason-outside-world)
                              radio-outside-world radio-retired))))))))

(az/defn pit-navigation-active?
  "A future pit call is not yet pit-lane driving. Share this boundary with
  manoeuvring so a called car can still clear an obstruction on the circuit."
  :- :bool [[pit-state :u8] [progress :f32] [box-progress :f32]]
  (or (ak/== pit-state pit-state-servicing)
      (ak/== pit-state pit-state-exiting)
      (and (ak/== pit-state pit-state-called) (>= progress 0.85)
           (< progress (+ box-progress 0.01)))))

(az/defn update-recovery!
  "Observe front and rear physical traffic before a low-speed manoeuvre.
  The model's existing destination/steering remains authoritative."
  :- :void [[normal-controls [:array racer-count driver/Control]]]
  (ensure-recovery!)
  (let [^:var bodies (std-mem/zeroes (az/type [:array racer-count physics/BodyState]))]
    (dotimes [i racer-count]
      (set! (az/index bodies i) (vehicle-pose i 0)))
  (dotimes [i racer-count]
    (let [racer (racer-pointer i)
          brain (brain-pointer i)
          body (vehicle-pose i 0)
          normal (az/index normal-controls i)
          ^{:var :f32} front-gap 1000.0
          ^{:var :f32} rear-gap 1000.0
          ^{:var :f32} rear-closing 0.0
          route (circuit/at-distance (* 4309.0 (az/field (az/deref racer) progress)) 0.0)
          aligned (> (std-math/cos (- (az/field (az/deref racer) heading)
                                      (az/field route heading))) 0.8)
          enabled (and (ak/== race-state race-state-running)
                       (ak/! (retired? i)) (ak/! (az/field (az/deref racer) finished))
                       (ak/! (and human-controlled (ak/== i 0)))
                       (ak/! (pit-navigation-active?
                              (az/field (az/deref racer) pit_state)
                              (az/field (az/deref racer) progress)
                              (pit-box-progress (az/field (az/deref racer) team))))
                       aligned (< (ak/abs (az/field normal lane)) recovery/corridor-half-width))]
      (dotimes [j racer-count]
        (when (ak/!= i j)
          (let [other (racer-pointer j)
                ahead (* 4309.0 (mod (- (az/field (az/deref other) progress)
                                        (az/field (az/deref racer) progress)) 1.0))
                behind (- 4309.0 ahead)
                side (* 50.0 (ak/abs (- (az/field (az/deref other) lane)
                                         (az/field (az/deref racer) lane))))]
            (when (and (< side (turnaround/recovery-side-clearance body (az/index bodies j)
                                  (az/field route heading)))
                       (< (az/field (az/deref other) speed) 0.002))
              (set! front-gap (ak/min front-gap ahead)))
            ;; Wide rear corridor includes cars close enough to move into our
            ;; path. Conservative max closing speed cannot hide a fast car
            ;; behind a nearer slow one. The check repeats while reversing.
            (when (and (< side 5.4) (< behind 500.0))
              (set! rear-gap (ak/min rear-gap behind))
              (set! rear-closing (ak/max rear-closing
                                   (* 1000.0 (az/field (az/deref other) speed))))))))
      (let [entry (recovery-pointer i)
            ^:var output (recovery/step (az/field (az/deref entry) state) normal
                     (az/index vehicle-controls i) body
                     (* 50.0 (az/field (az/deref brain) lane_target))
                     front-gap rear-gap rear-closing enabled)]
        (when (and (ak/!= (az/field (az/field output state) phase) 0)
                   (< (az/field normal speed) 3.0))
          (set! (az/field output control)
            (turnaround/guard-recovery-control (az/field output control) body
              bodies racer-count i (az/field output gear) dynamics-world
              recovery/corridor-half-width)))
        ;; Inspection must report the same pedals that physics will receive.
        (set! (az/deref entry) output)
        (set! (az/index vehicle-controls i) (az/field output control)))))))

(az/defn update-turnarounds!
  "Physical body-relative manoeuvring takes precedence only after a spin.
  In ordinary driving the traffic/recovery controls remain unchanged."
  :- :void [[normal-controls [:array racer-count driver/Control]]]
  (ensure-turnaround!)
  (let [^:var bodies (std-mem/zeroes (az/type [:array racer-count physics/BodyState]))]
    (dotimes [i racer-count]
      (set! (az/index bodies i) (vehicle-pose i 0)))
    (dotimes [i racer-count]
      (let [racer (racer-pointer i)
            entry (turnaround-pointer i)
            previous (az/field (az/deref entry) state)
            normal (az/index normal-controls i)
            enabled (and (ak/== race-state race-state-running)
                         (ak/! (retired? i)) (ak/! (az/field (az/deref racer) finished))
                         (ak/! (and human-controlled (ak/== i 0)))
                         ;; Physical support extends to +/-35m; the planner
                         ;; queries the actual containment walls before moving.
                         (< (ak/abs (az/field normal lane)) 30.0)
                         (ak/! (pit-navigation-active?
                                 (az/field (az/deref racer) pit_state)
                                 (az/field (az/deref racer) progress)
                                 (pit-box-progress (az/field (az/deref racer) team)))))
            output (turnaround/step previous normal (az/index bodies i)
                                   bodies racer-count i enabled dynamics-world)]
        (set! (az/deref entry) output)
        (when (or (az/field previous active) (az/field (az/field output state) active))
          (set! (az/index vehicle-controls i) (az/field output control))
          (set! (az/field (az/deref (recovery-pointer i)) gear)
                (az/field (az/field output state) gear)))))))

(az/defn update-lap-timing!
  "Observe progression, never control it. Pauses/countdown do not accrue time."
  :- :void []
  (ensure-lap-timing!)
  (dotimes [i racer-count]
    (let [entry (lap-timing-pointer i)
          racer (racer-pointer i)]
      (set! (az/deref entry)
            (lap-timing/observe (az/deref entry) simulation-tick
              (az/field (az/deref racer) lap)
              (ak/== race-state race-state-running)
              (or (retired? i) (az/field (az/deref racer) finished)))))))

(az/defn step-dynamics!
  "Apply controls and solve contacts; derive telemetry from the resulting bodies."
  :- :void []
  (let [normal-controls vehicle-controls]
    (brake-for-traffic!)
    (update-recovery! normal-controls)
    (update-turnarounds! normal-controls))
  (enforce-language-holds!)
  (dotimes [i racer-count]
    ;; A finish is a classification result, not evidence that the car is still
    ;; driveable. Disable final pedals after a rollover even during cooldown.
    (set! (az/index vehicle-controls i)
      (driver/stop-if-overturned (az/index vehicle-controls i) (vehicle-pose i 0)))
    (when (> (az/index contact-cooldowns i) 0)
      (set! (az/index contact-cooldowns i) (- (az/index contact-cooldowns i) 1))))
  (dotimes [_ (ak/divTrunc physics/step-rate 120)]
    (dotimes [i racer-count]
      (let [control (az/index vehicle-controls i)]
        (physics/drive-in-gear! (az/deref (vehicle-pointer i))
          (az/field control throttle) (az/field control brake) (az/field control steering)
          (az/field (az/deref (recovery-pointer i)) gear))))
    (physics/step! dynamics-world)
    (physical-contacts!))
  (dotimes [i racer-count]
    (let [racer (racer-pointer i)
          state (vehicle-pose i 0)
          projected (track/project (* (az/field state x) 0.001) (* (az/field state y) 0.001))
          progress (az/field projected progress)
          previous (az/field (az/deref racer) progress)
          checkpoint (checkpoint-for-progress progress)
          expected (mod (+ (az/index lap-checkpoints i) 1) 4)]
      ;; A reverse crossing or a crash/cut skipping a checkpoint cannot create
      ;; a completed lap. Ranking still reflects the measured body position.
      (when (and (ak/== checkpoint expected)
                 (or (and (> progress previous) (< (- progress previous) 0.05))
                     (and (> previous 0.95) (< progress 0.05))))
        (set! (az/index lap-checkpoints i) checkpoint)
        (when (and (ak/== checkpoint 0) (ak/! (retired? i))
                   (ak/! (az/field (az/deref racer) finished)))
          (complete-lap! racer)))
      (set! (az/field (az/deref racer) progress) progress)
      (set! (az/field (az/deref racer) lane) (az/field projected lane))
      (set! (az/field (az/deref racer) x) (* (az/field state x) 0.001))
      (set! (az/field (az/deref racer) y) (* (az/field state y) 0.001))
      (set! (az/field (az/deref racer) speed)
            (* 0.001 (ak/sqrt (+ (* (az/field state vx) (az/field state vx))
                                 (* (az/field state vy) (az/field state vy))))))
      (set! (az/field (az/deref racer) heading)
            (std-math/atan2 (* 2.0 (+ (* (az/field state qw) (az/field state qz))
                                      (* (az/field state qx) (az/field state qy))))
                            (- 1.0 (* 2.0 (+ (* (az/field state qy) (az/field state qy))
                                              (* (az/field state qz) (az/field state qz))))))))))

(az/defn step-pit!
  "Pit strategy controls pedals; service starts only after a physical stop."
  :- :bool [[index :usize]]
  (let [racer (racer-pointer index) brain (brain-pointer index)
        team (team-pointer (ak/intCast (az/field (az/deref racer) team)))
        car (az/deref (vehicle-pointer index))
        progress (az/field (az/deref racer) progress)
        box-progress (pit-box-progress (az/field (az/deref racer) team))
        state (az/field (az/deref racer) pit_state)
        ^{:var :bool} owns-control false]
    (cond
      (and (ak/== state pit-state-called)
           (pit-navigation-active? state progress box-progress))
      (let [remaining (* (- box-progress progress) 4309.0)
            requested (ak/sqrt (* 6.0 (ak/max 0.0 (- remaining 0.5))))
            control (driver/follow-pit car requested 6.0)]
        (set! owns-control true)
        (set! (az/index vehicle-controls index) control)
        (when (and (< (ak/abs remaining) 1.0) (< (az/field control speed) 0.02))
          (set! (az/field (az/deref racer) pit_state) pit-state-servicing)
          (set! (az/field (az/deref racer) pit_seconds)
                (+ pit-service-seconds (* (az/field (az/deref racer) damage) pit-repair-extra-seconds)))
          (radio-message! index radio-source-driver radio-boxing-now)))

      (ak/== state pit-state-servicing)
      (do
        (set! owns-control true)
        (let [^:var control (driver/follow-pit car 0.0 6.0)]
          (set! (az/field control throttle) 0.0)
          (set! (az/field control brake) 1.0)
          (set! (az/index vehicle-controls index) control)
          ;; A collision moving the car pauses service; there is no position or
          ;; velocity lock that could turn it into an immovable obstacle.
          (when (< (az/field control speed) 0.02)
            (set! (az/field (az/deref racer) pit_seconds)
                  (ak/max 0.0 (- (az/field (az/deref racer) pit_seconds) fixed-step)))))
        (when (<= (az/field (az/deref racer) pit_seconds) 0.0)
          (let [repaired (> (az/field (az/deref racer) damage) 0.01)]
            (set! (az/field (az/deref racer) tire_condition) 1.0)
            (set! (az/field (az/deref racer) damage) 0.0)
            (set! (az/field (az/deref racer) tire_stage) 0)
            (set! (az/field (az/deref racer) damage_stage) 0)
            (set! (az/field (az/deref racer) pit_waiting) false)
            (set! (az/field (az/deref racer) pit_state) pit-state-exiting)
            (set! (az/field (az/deref racer) pit_stops) (+ (az/field (az/deref racer) pit_stops) 1))
            (set! (az/field (az/deref team) pit_stops) (+ (az/field (az/deref team) pit_stops) 1))
            (set! (az/field (az/deref team) pit_occupant) no-pit-occupant)
            (set! (az/field (az/deref team) instruction) team-action-hold)
            (set! pit-stop-count (+ pit-stop-count 1))
            (radio-message! index radio-source-strategist (if repaired radio-car-repaired radio-fresh-tires))
            (set! (az/field (az/deref brain) lane_target) 0.0)
            (set! (az/field (az/deref brain) target_speed) 0.070)
            (set! (az/field (az/deref brain) urgent) true)
            (set! (az/field (az/deref brain) next_decision_tick) simulation-tick))))

      (ak/== state pit-state-exiting)
      (do
        (set! owns-control true)
        (set! (az/index vehicle-controls index) (driver/follow-pit car 22.0 6.0))
        (when (and (< progress 0.5) (>= progress 0.055))
          (set! (az/field (az/deref racer) pit_state) pit-state-track)))
      :else (set! owns-control false))
    owns-control))

(az/defn step-racer!
  :-
  :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        reference-driver (and human-controlled (ak/== index 0))
        language-driver (az/field (az/index language-drivers index) enabled)]
    (when (retired? index)
      (set! (az/index vehicle-controls index)
            (driver/Control {:throttle 0.0 :brake 1.0 :steering 0.0
                             :progress (az/field (az/deref racer) progress)
                             :lane (* 50.0 (az/field (az/deref racer) lane))
                             :speed (* 1000.0 (az/field (az/deref racer) speed))}))
      (ak/return))
    (when reference-driver
      (apply-human-control!))
    (when (and (ak/! replay-active) (ak/! reference-driver) (ak/! language-driver))
      (install-worker-result! index))
    (when (and (ak/! replay-active) (ak/! reference-driver) (ak/! language-driver))
      (set! _ (expire-pending-decision! index)))
    (when (and (ak/! replay-active)
               (ak/! reference-driver)
               (ak/! language-driver)
               (ak/! (az/field (az/deref racer) finished))
               (ak/! (az/field (az/deref brain) pending))
               (>= simulation-tick
                   (az/field (az/deref brain) next_decision_tick)))
      (let [urgent (az/field (az/deref brain) urgent)]
        (when (ak/! (submit-worker-request! index urgent))
          (make-decision! index urgent deadline-on-time))))
    (when (ak/== race-state race-state-running)
      (apply-item! index))
    (let [car (az/deref (vehicle-pointer index))
          running (ak/== race-state race-state-running)
          finished (az/field (az/deref racer) finished)
          boosted (> (az/field (az/deref racer) boost_seconds) 0.0)
          grip (+ 0.72 (* 0.28 (az/field (az/deref racer) tire_condition)))
          power-condition (- 1.0 (* 0.25 (az/field (az/deref racer) damage)))
          ;; Keep finishers moving on a controlled cooldown while others are
          ;; racing. Stopping on the line/pit merge can trap the final runner.
          ;; Classification is already fixed by finish_tick, not this motion.
          requested (if running
                      (if finished (ak/as :f32 30.0)
                        (* 1000.0 grip power-condition
                           (+ (az/field (az/deref brain) target_speed) (if boosted (ak/as :f32 0.035) 0.0))))
                      0.0)
          ^:var control (driver/follow-lane-plan car requested
                          (* (az/field (az/deref brain) lane_target) 50.0)
                          (ak/& (az/index lane-plans index)))]
      (when (ak/! running)
        (set! (az/field control throttle) 0.0)
        (set! (az/field control brake) 1.0))
      (when (and reference-driver running)
        (set! (az/field control throttle) human-throttle)
        (set! (az/field control brake) human-brake)
        (set! (az/field control steering) (* human-steering 0.45)))
      (set! (az/index vehicle-controls index) control))
    ;; A car may cross the finish while still inside the parallel pit lane.
    ;; Complete the actual paved exit before joining its cooldown lap.
    (when (and (ak/== race-state race-state-running)
               (az/field (az/deref racer) finished)
               (ak/== (az/field (az/deref racer) pit_state) pit-state-exiting))
      (set! _ (step-pit! index)))
    (when (and (ak/== race-state race-state-running)
               (ak/! (az/field (az/deref racer) finished)))
      (update-tire-strategy! index)
      (set! _ (step-pit! index))
      (set! (az/field (az/deref racer) stun_seconds)
            (ak/max 0.0 (- (az/field (az/deref racer) stun_seconds) fixed-step)))
      (set! (az/field (az/deref racer) boost_seconds)
            (ak/max 0.0 (- (az/field (az/deref racer) boost_seconds) fixed-step)))
      (set! (az/field (az/deref racer) pickup_cooldown)
            (ak/max 0.0 (- (az/field (az/deref racer) pickup_cooldown) fixed-step)))
      (collect-item! index))))

(az/defn resolve-racer-contacts!
  "Contact resolution is exclusively performed by Box3D inside step-dynamics!."
  :- :void []
  (physical-contacts!))

(az/defn classification-progress :- :f32 [[index :usize]]
  (if (retired? index)
    (let [entry (az/deref (status-pointer index))]
      (+ (ak/as :f32 (ak/floatFromInt (az/field entry lap)))
         (az/field entry progress)))
    (absolute-progress (racer-pointer index))))

(az/defn update-ranks!
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

                  (and (retired? other-index) (ak/! (retired? index))) false
                  (and (retired? index) (ak/! (retired? other-index))) true

                  :else
                  (let [a (classification-progress other-index)
                        b (classification-progress index)]
                    (or (> a b) (and (ak/== a b) (< other-index index)))))]
            (when other-ahead
              (set! rank (+ rank 1))))))
      (set! (az/field (az/deref racer) rank) rank))))

(az/defn initialize!
  "Create the Flecs world and exactly twenty independently scheduled AI racers."
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
      (set! team-component
            (register-component flecs-world "RacingTeam"
                                (ak/sizeOf Team) (ak/alignOf Team)))
      (flecs/ecs_add_id flecs-world racer-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world brain-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world hazard-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world team-component flecs/EcsSparse)
      (dotimes [index team-count]
        (let [entity (flecs/ecs_new flecs-world)
              identifier (ak/as :u8 (ak/intCast index))
              first-driver (ak/as :u8 (ak/intCast (* index protocol/drivers-per-team)))
              team
              (Team {:id identifier
                     :driver_a first-driver
                     :driver_b (+ first-driver 1)
                     :pit_occupant no-pit-occupant
                     :instruction team-action-hold
                     :radio_code radio-none
                     :radio_target first-driver
                     :pending false
                     :pit_stops 0
                     :reserved 0
                     :pending_revision 0
                     :pending_tick 0
                     :decision_revision 0
                     :next_decision_tick (ak/intCast (* index 7))
                     :decisions 0
                     :invalid_decisions 0
                     :last_latency_us 0
                     :average_latency_us 0
                     :radio_sequence 0})]
          (set! (az/index team-entities index) entity)
          (flecs/ecs_set_id flecs-world entity team-component
                            (ak/sizeOf Team) (ak/& team))))
      (dotimes [index racer-count]
        (let [entity (flecs/ecs_new flecs-world)
              identifier (ak/as :u8 (ak/intCast index))
              seed-slot
              (ak/as :usize
                     (ak/intCast
                      (mod race-seed
                           (ak/as :u64 (ak/intCast racer-count)))))
              grid-index (mod (+ index seed-slot) racer-count)
              ;; Staggered 2-column grid, eight metres between rows.
              progress (* (ak/as :f32 (ak/floatFromInt grid-index)) (/ 4.0 4309.0))
              ^{:zig/type :f32}
              lane (if (ak/== (mod grid-index 2) 0) -0.05 0.05)
              racer (Racer {:id identifier
                            :rank (+ identifier 1)
                            :lap 0
                            :finished false
                            :item item-none
                            :shielded false
                            :team (ak/intCast (ak/divTrunc index protocol/drivers-per-team))
                            :pit_state pit-state-track
                            :tire_stage 0
                            :pit_waiting false
                            :pit_stops 0
                            :damage_stage 0
                            :radio_code radio-none
                            :radio_source radio-source-none
                            :tire_condition 1.0
                            :damage 0.0
                            :pit_seconds 0.0
                            :progress progress
                            :lane lane
                            :speed 0.0
                            :x 0.0
                            :y 0.0
                            :heading 0.0
                            :stun_seconds 0.0
                            :boost_seconds 0.0
                            :pickup_cooldown 0.0
                            :radio_revision 0
                            :finish_tick 0})
              brain (RacerBrain
                     {:racer_id identifier
                      :pace 1
                      :item_action action-hold
                      :target identifier
                      :source telemetry/source-fallback
                      :urgent true
                      :pending false
                      :pending_urgent false
                      :pending_target identifier
                      :lane_target lane
                      :target_speed 0.07
                      :aggression (+ 0.28 (* 0.595 (/ (ak/as :f32 (ak/floatFromInt index))
                                                     (ak/as :f32 (ak/floatFromInt (- racer-count 1))))))
                      :patience (- 0.86 (* 0.49 (/ (ak/as :f32 (ak/floatFromInt index))
                                                    (ak/as :f32 (ak/floatFromInt (- racer-count 1))))))
                      :risk (+ 0.20 (* 0.075 (ak/as :f32 (ak/floatFromInt (mod (+ index 3) 8)))))
                      :next_decision_tick (ak/as :u64 (ak/intCast (* index 5)))
                      :pending_revision 0
                      :pending_tick 0
                      :last_decision_tick 0
                      :decision_revision 0
                      :decisions 0
                      :urgent_decisions 0
                      :invalid_decisions 0
                      :deadline_misses 0
                      :last_latency_us 0
                      :average_latency_us 0})]
          (set! (az/index entities index) entity)
          (flecs/ecs_set_id flecs-world entity racer-component
                            (ak/sizeOf Racer) (ak/& racer))
          (flecs/ecs_set_id flecs-world entity brain-component
                            (ak/sizeOf RacerBrain) (ak/& brain))
          (update-position! (racer-pointer index))))
      ;; The seeded grid permutation is authoritative from tick zero. Populate
      ;; ranks before the first observation so the opening prompt cannot claim
      ;; rank 1 while naming a physically farther-along opponent.
      (update-ranks!)
      (dotimes [slot hazard-capacity]
        (let [entity (flecs/ecs_new flecs-world)
              hazard (std-mem/zeroes (az/type Hazard))]
          (set! (az/index hazard-entities slot) entity)
          (flecs/ecs_set_id flecs-world entity hazard-component
                            (ak/sizeOf Hazard) (ak/& hazard)))))
    (set! initialized true))
  (ensure-dynamics!)
  (ensure-race-status!)
  (ensure-recovery!)
  (ensure-lap-timing!)
  initialized)

(az/defn coast-finished!
  "Use the brakes after finishing; the car remains a movable physical body."
  :- :void [[index :usize]]
  (let [^:var control (driver/follow (az/deref (vehicle-pointer index)) 0.0 0.0)]
    (set! (az/field control throttle) 0.0)
    (set! (az/field control brake) 1.0)
    (set! (az/index vehicle-controls index) control)))

(az/defn step!
  "Advance one deterministic 120 Hz simulation tick."
  :-
  :void
  []
  (set! _ (initialize!))
  (update-thought-cadence!)
  (step-language-driving!)
  (when (ak/! paused)
    (dotimes [index racer-count]
      (step-racer! index))
    (step-dynamics!)
    (update-retirements!)
    (update-lap-timing!))
  (when (and (ak/! paused) (ak/!= race-state race-state-finished))
    (when (> countdown-ticks 0)
      (set! countdown-ticks (- countdown-ticks 1))
      (when (ak/== countdown-ticks 0)
        (set! race-state race-state-running)))
    (when replay-active
      (install-replay-intents!))
    (when (ak/== race-state race-state-running)
      (step-hazards!)
      (dotimes [team-index team-count]
        (step-team-strategist! team-index)))
    (update-ranks!)
    (let [^{:var true :zig/type :u8} terminal-count 0]
      (dotimes [index racer-count]
        (let [racer (racer-pointer index)]
          (when (or (az/field (az/deref racer) finished) (retired? index))
            (set! terminal-count (+ terminal-count 1)))
          (telemetry/resolve-due-outcomes!
           (az/field (az/deref racer) id)
           simulation-tick
           (az/field (az/deref racer) rank)
           (az/field (az/deref racer) lap)
           (az/field (az/deref racer) progress)
           (az/field (az/deref racer) finished))))
      (when (ak/== terminal-count racer-count)
        (set! race-state race-state-finished)))
    (set! _ (flecs/ecs_progress world fixed-step))
    (set! human-use-item false)
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
    (RacerView {:valid false :id identifier :rank 0 :lap 0 :checkpoint 0
                :finished false
                :item 0 :shielded false :team 0 :teammate 0
                :pit_state pit-state-track :pit_stops 0
                :damage_stage 0 :radio_code radio-none
                :radio_source radio-source-none
                :team_instruction team-action-hold :team_pending false
                :source telemetry/source-fallback
                :pending false :pending_urgent false :item_action 0
                :tire_condition 0.0 :damage 0.0 :pit_seconds 0.0
                :progress 0.0 :lane 0.0 :speed 0.0
                :x 0.0 :y 0.0 :heading 0.0 :lane_target 0.0
                :target_speed 0.0 :target 0
                :pending_age_ticks 0 :intent_age_ticks 0
                :decision_revision 0 :decisions 0 :deadline_misses 0
                :last_latency_us 0 :average_latency_us 0
                :radio_revision 0
                :team_decision_revision 0 :team_decisions 0
                :team_last_latency_us 0 :team_average_latency_us 0
                :finish_tick 0})
    (let [index (ak/as :usize (ak/intCast identifier))
          racer (racer-pointer index)
          brain (brain-pointer index)
          team (team-pointer
                (ak/as :usize
                       (ak/intCast (az/field (az/deref racer) team))))]
      (RacerView
       {:valid true
        :id identifier
        :rank (az/field (az/deref racer) rank)
        :lap (az/field (az/deref racer) lap)
        :checkpoint
        (checkpoint-for-progress (az/field (az/deref racer) progress))
        :finished (az/field (az/deref racer) finished)
        :item (az/field (az/deref racer) item)
        :shielded (az/field (az/deref racer) shielded)
        :team (az/field (az/deref racer) team)
        :teammate (teammate-id identifier)
        :pit_state (az/field (az/deref racer) pit_state)
        :pit_stops (az/field (az/deref racer) pit_stops)
        :damage_stage (az/field (az/deref racer) damage_stage)
        :radio_code (az/field (az/deref racer) radio_code)
        :radio_source (az/field (az/deref racer) radio_source)
        :team_instruction (az/field (az/deref team) instruction)
        :team_pending (az/field (az/deref team) pending)
        :source (az/field (az/deref brain) source)
        :pending (az/field (az/deref brain) pending)
        :pending_urgent (az/field (az/deref brain) pending_urgent)
        :item_action (az/field (az/deref brain) item_action)
        :tire_condition (az/field (az/deref racer) tire_condition)
        :damage (az/field (az/deref racer) damage)
        :pit_seconds (az/field (az/deref racer) pit_seconds)
        :progress (az/field (az/deref racer) progress)
        :lane (az/field (az/deref racer) lane)
        :speed (az/field (az/deref racer) speed)
        :x (az/field (az/deref racer) x)
        :y (az/field (az/deref racer) y)
        :heading (az/field (az/deref racer) heading)
        :lane_target (az/field (az/deref brain) lane_target)
        :target_speed (az/field (az/deref brain) target_speed)
        :target (az/field (az/deref brain) target)
        :pending_age_ticks
        (if (and (az/field (az/deref brain) pending)
                 (>= simulation-tick
                     (az/field (az/deref brain) pending_tick)))
          (- simulation-tick (az/field (az/deref brain) pending_tick))
          0)
        :intent_age_ticks
        (if (>= simulation-tick
                (az/field (az/deref brain) last_decision_tick))
          (- simulation-tick (az/field (az/deref brain) last_decision_tick))
          0)
        :decision_revision (az/field (az/deref brain) decision_revision)
        :decisions (az/field (az/deref brain) decisions)
        :deadline_misses (az/field (az/deref brain) deadline_misses)
        :last_latency_us (az/field (az/deref brain) last_latency_us)
        :average_latency_us (az/field (az/deref brain) average_latency_us)
        :radio_revision (az/field (az/deref racer) radio_revision)
        :team_decision_revision (az/field (az/deref team) decision_revision)
        :team_decisions (az/field (az/deref team) decisions)
        :team_last_latency_us (az/field (az/deref team) last_latency_us)
        :team_average_latency_us (az/field (az/deref team) average_latency_us)
        :finish_tick (az/field (az/deref racer) finish_tick)}))))

(az/defn hazard-view
  "Inspect one stable pooled combat-object slot."
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
        ^{:var true :zig/type :u64} deadline-misses 0
        ^{:var true :zig/type :u64} max-intent-age-ticks 0
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
              (+ invalid-decisions (az/field (az/deref brain) invalid_decisions)))
        (set! deadline-misses
              (+ deadline-misses (az/field (az/deref brain) deadline_misses)))
        (when (>= simulation-tick
                  (az/field (az/deref brain) last_decision_tick))
          (set! max-intent-age-ticks
                (ak/max max-intent-age-ticks
                        (- simulation-tick
                           (az/field (az/deref brain) last_decision_tick)))))))
    (dotimes [slot hazard-capacity]
      (when (az/field (az/deref (hazard-pointer slot)) active)
        (set! active-hazards (+ active-hazards 1))))
    (RaceSnapshot
     {:initialized initialized
      :paused paused
      :state race-state
      :human_controlled human-controlled
      :countdown_ticks countdown-ticks
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
      :deadline_misses deadline-misses
      :max_intent_age_ticks max-intent-age-ticks
      :items_used item-use-count
      :hits hit-count
      :contacts contact-count
      :active_hazards active-hazards
      :hazards_spawned hazard-spawn-count
      :pit_stops pit-stop-count
      :team_radio_messages team-radio-count
      :accidents accident-count
      :team_ai_decisions team-ai-decision-count
      :world_address (if (ak/== world null)
                       0
                       (ak/intFromPtr (az/unwrap world)))})))

(az/defn mix-state-word
  :-
  :u64
  [[fingerprint :u64]
   [word :u64]]
  (ak/*% (ak/bit-xor fingerprint word) 1099511628211))

(az/defn mix-state-f32
  :-
  :u64
  [[fingerprint :u64]
   [value :f32]]
  (let [^{:zig/type :u32} bits (ak/bitCast value)]
    (mix-state-word fingerprint (ak/as :u64 bits))))

(az/defn state-fingerprint
  "Hash canonical gameplay state field-by-field without struct padding,
  addresses, worker timings, or replay-control bookkeeping."
  :-
  :u64
  []
  (set! _ (initialize!))
  (let [^{:var true :zig/type :u64} fingerprint 14695981039346656037]
    (set! fingerprint (mix-state-word fingerprint simulation-tick))
    (set! fingerprint (mix-state-word fingerprint race-seed))
    (set! fingerprint
          (mix-state-word fingerprint (ak/as :u64 race-state)))
    (set! fingerprint
          (mix-state-word fingerprint (ak/as :u64 countdown-ticks)))
    (set! fingerprint
          (mix-state-word fingerprint (if human-controlled 1 0)))
    (set! fingerprint (mix-state-f32 fingerprint human-steering))
    (set! fingerprint (mix-state-f32 fingerprint human-throttle))
    (set! fingerprint (mix-state-f32 fingerprint human-brake))
    (set! fingerprint
          (mix-state-word fingerprint (if human-use-item 1 0)))
    (set! fingerprint (mix-state-word fingerprint item-use-count))
    (set! fingerprint (mix-state-word fingerprint hit-count))
    (set! fingerprint (mix-state-word fingerprint contact-count))
    (set! fingerprint (mix-state-word fingerprint hazard-spawn-count))
    (set! fingerprint (mix-state-word fingerprint pit-stop-count))
    (set! fingerprint (mix-state-word fingerprint team-radio-count))
    (dotimes [index racer-count]
      (let [racer (racer-pointer index)
            brain (brain-pointer index)
            classification (retirement-view index)]
        (set! fingerprint (mix-state-word fingerprint (if (az/field classification retired) 1 0)))
        (set! fingerprint (mix-state-word fingerprint (az/field classification reason)))
        (set! fingerprint (mix-state-word fingerprint (az/field classification invalid_ticks)))
        (set! fingerprint (mix-state-word fingerprint (az/field classification retired_tick)))
        (set! fingerprint (mix-state-word fingerprint (az/field classification lap)))
        (set! fingerprint (mix-state-f32 fingerprint (az/field classification progress)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) id))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) rank))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) lap))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (if (az/field (az/deref racer) finished) 1 0)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) item))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (if (az/field (az/deref racer) shielded) 1 0)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) team))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) pit_state))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) pit_stops))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref racer) radio_code))))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) tire_condition)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) pit_seconds)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref racer) radio_revision)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) progress)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref racer) lane)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref racer) speed)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref racer) x)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref racer) y)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref racer) heading)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) stun_seconds)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) boost_seconds)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref racer) pickup_cooldown)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref racer) finish_tick)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref brain) racer_id))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref brain) pace))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref brain) item_action))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref brain) target))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (if (az/field (az/deref brain) urgent) 1 0)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (if (az/field (az/deref brain) pending) 1 0)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref brain) lane_target)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref brain) target_speed)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref brain) aggression)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref brain) patience)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref brain) risk)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref brain) next_decision_tick)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref brain) decision_revision)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref brain) decisions)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref brain) urgent_decisions)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref brain) invalid_decisions)))))
    (dotimes [index racer-count]
      (set! fingerprint
            (mix-state-word
             fingerprint (ak/as :u64 (az/index contact-cooldowns index))))
      (let [entry (recovery-view index) state (az/field entry state)]
        (set! fingerprint (mix-state-word fingerprint (ak/as :u64 (az/field state phase))))
        (set! fingerprint (mix-state-word fingerprint (ak/as :u64 (az/field state waiting_ticks))))
        (set! fingerprint (mix-state-f32 fingerprint (az/field state start_x)))
        (set! fingerprint (mix-state-f32 fingerprint (az/field state start_y)))
        (set! fingerprint (mix-state-word fingerprint
                            (ak/as :u64 (ak/intCast (+ (ak/as :i32 (az/field entry gear)) 1)))))))
    (dotimes [index team-count]
      (let [team (team-pointer index)]
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref team) pit_occupant))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref team) pit_stops))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (az/field (az/deref team) radio_sequence)))))
    (dotimes [slot hazard-capacity]
      (let [hazard (hazard-pointer slot)]
        (set! fingerprint
              (mix-state-word fingerprint
                              (if (az/field (az/deref hazard) active) 1 0)))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref hazard) kind))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref hazard) owner))))
        (set! fingerprint
              (mix-state-word fingerprint
                              (ak/as :u64
                                     (az/field (az/deref hazard) target))))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref hazard) progress)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref hazard) lane)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref hazard) speed)))
        (set! fingerprint
              (mix-state-f32 fingerprint
                             (az/field (az/deref hazard) arming_seconds)))
        (set! fingerprint
              (mix-state-f32 fingerprint (az/field (az/deref hazard) ttl)))
        (set! fingerprint
              (mix-state-word
               fingerprint (az/field (az/deref hazard) decision_revision)))))
    fingerprint))

(az/defn toggle-paused!
  :-
  :bool
  []
  (set! paused (ak/! paused))
  paused)

(az/defn configure-countdown!
  "Set the deterministic start countdown in simulation ticks and apply it to
  the current race. Desktop uses 360 ticks (three seconds); headless fixtures
  can keep zero for maximum-speed deterministic evaluation."
  :-
  :void
  [[ticks :u16]]
  (set! configured-countdown-ticks ticks)
  (set! countdown-ticks ticks)
  (set! race-state
        (if (> ticks 0) race-state-countdown race-state-running)))

(az/defn set-items-enabled!
  "Enable normal pickups or run a deterministic no-item race. The setting is
  explicit and survives reset so tests and nREPL experiments can own it."
  :-
  :bool
  [[enabled :bool]]
  (do
    (set! items-enabled enabled)
    items-enabled))

(az/defn set-human-controlled!
  "Enable or disable optional keyboard/gamepad control for racer 0. All twenty
  racers remain AI-controlled by default. Switching invalidates any old
  in-flight racer-0 result without restarting the world or workers."
  :-
  :bool
  [[enabled :bool]]
  (do
    (set! human-controlled enabled)
    (when initialized
      (let [brain (brain-pointer 0)]
        (set! (az/field (az/deref brain) pending) false)
        (set! (az/field (az/deref brain) pending_urgent) false)
        (set! (az/field (az/deref brain) pending_target) 0)
        (set! (az/field (az/deref brain) pending_revision) 0)
        (set! (az/field (az/deref brain) pending_tick) 0)
        (set! (az/field (az/deref brain) urgent) false)
        (set! (az/field (az/deref brain) item_action) action-hold)
        (set! (az/field (az/deref brain) source)
              (if enabled telemetry/source-human telemetry/source-fallback))
        (set! (az/field (az/deref brain) decision_revision)
              (next-decision-revision!))
        (set! (az/field (az/deref brain) last_decision_tick) simulation-tick)
        (set! (az/field (az/deref brain) next_decision_tick) simulation-tick)))
    human-controlled))

(az/defn set-human-input!
  "Install normalized reference-driver input. Values are clamped again inside
  the fixed-step controller, so keyboard, gamepad, and nREPL use one safe path."
  :-
  :void
  [[steering :f32]
   [throttle :f32]
   [brake :f32]
   [use-item :bool]]
  (set! human-steering steering)
  (set! human-throttle throttle)
  (set! human-brake brake)
  (set! human-use-item use-item))

(az/defn human-control-snapshot
  "Inspect the exact reference-driver input currently consumed by native code."
  :-
  HumanControlSnapshot
  []
  (HumanControlSnapshot {:enabled human-controlled
                         :steering human-steering
                         :throttle human-throttle
                         :brake human-brake
                         :use_item human-use-item}))

(az/defn set-race-seed!
  "Choose the deterministic starting-grid and pickup permutation used by the
  next explicit `reset!`. The running world is never mutated implicitly."
  :-
  :void
  [[seed :u64]]
  (set! race-seed seed))

(az/defn configure-racer-state!
  "Safely edit one live racer's physical/item state for REPL scenarios. The
  fixed-step controller remains authoritative after this explicit mutation."
  :-
  :bool
  [[identifier :u8]
   [progress :f32]
   [lane :f32]
   [speed :f32]
   [item :u8]
   [shielded :bool]]
  (do
    (set! _ (initialize!))
    (if (or (>= identifier racer-count) (> item item-surge))
      false
      (let [racer (racer-pointer identifier)]
        (set! (az/field (az/deref racer) progress)
              (track/wrap-progress progress))
        (set! (az/field (az/deref racer) lane)
              (ak/min 0.075 (ak/max -0.075 lane)))
        (set! (az/field (az/deref racer) speed)
              (ak/min 0.16 (ak/max 0.0 speed)))
        (set! (az/field (az/deref racer) item) item)
        (set! (az/field (az/deref racer) shielded) shielded)
        (set! (az/field (az/deref racer) stun_seconds) 0.0)
        (set! (az/field (az/deref racer) boost_seconds) 0.0)
        (set! (az/field (az/deref racer) pickup_cooldown) 1.0)
        (update-position! racer)
        (update-ranks!)
        true))))

(az/defn configure-racer-tires!
  "Set bounded tire condition for a live REPL/test scenario. Normal racing
  immediately resumes authoritative wear, grip, radio, and pit strategy."
  :-
  :bool
  [[identifier :u8]
   [condition :f32]]
  (do
    (set! _ (initialize!))
    (if (>= identifier racer-count)
      false
      (let [racer (racer-pointer (ak/intCast identifier))]
        (set! (az/field (az/deref racer) tire_condition)
              (ak/min 1.0 (ak/max 0.0 condition)))
        (set! (az/field (az/deref racer) tire_stage) 0)
        (set! (az/field (az/deref racer) pit_waiting) false)
        true))))

(az/defn configure-racer-damage!
  "Set bounded persistent car damage for a live REPL/test scenario. The team
  strategist observes it on the next fixed tick and can call the car to repair."
  :-
  :bool
  [[identifier :u8]
   [damage :f32]]
  (do
    (set! _ (initialize!))
    (if (>= identifier racer-count)
      false
      (let [racer (racer-pointer (ak/intCast identifier))]
        (set! (az/field (az/deref racer) damage)
              (ak/min 1.0 (ak/max 0.0 damage)))
        (set! (az/field (az/deref racer) damage_stage) 0)
        (set! (az/field (az/deref racer) pit_waiting) false)
        true))))

(az/defn configure-racer-intent!
  "Install one bounded live intent for REPL scenarios. It uses the same
  RacerBrain fields as model output and remains active until the next thought."
  :-
  :bool
  [[identifier :u8]
   [lane-target :f32]
   [target-speed :f32]
   [item-action :u8]
   [target :u8]]
  (do
    (set! _ (initialize!))
    (if (or (>= identifier racer-count)
            (>= target racer-count)
            (> item-action action-use))
      false
      (let [brain (brain-pointer identifier)]
        (set! (az/field (az/deref brain) lane_target)
              (ak/min 0.075 (ak/max -0.075 lane-target)))
        (set! (az/field (az/deref brain) target_speed)
              (ak/min 0.16 (ak/max 0.0 target-speed)))
        (set! (az/field (az/deref brain) item_action) item-action)
        (set! (az/field (az/deref brain) target) target)
        (set! (az/field (az/deref brain) source) telemetry/source-fallback)
        (set! (az/field (az/deref brain) pending) false)
        (set! (az/field (az/deref brain) pending_urgent) false)
        (set! (az/field (az/deref brain) pending_target) target)
        (set! (az/field (az/deref brain) pending_revision) 0)
        (set! (az/field (az/deref brain) pending_tick) 0)
        (set! (az/field (az/deref brain) urgent) false)
        (set! (az/field (az/deref brain) next_decision_tick)
              (+ simulation-tick current-ordinary-thought-ticks))
        true))))

(az/defn reset!
  "Explicitly recreate the Flecs race. Ordinary hot reload never calls this."
  :-
  :void
  []
  (destroy-dynamics!)
  (when (ak/!= world null)
    (set! _ (flecs/ecs_fini world)))
  (set! world null)
  (set! status-component 0)
  (set! recovery-component 0)
  (set! turnaround-component 0)
  (set! lap-timing-component 0)
  (set! initialized false)
  (set! paused false)
  (set! countdown-ticks configured-countdown-ticks)
  (set! race-state
        (if (> countdown-ticks 0)
          race-state-countdown
          race-state-running))
  (set! human-steering 0.0)
  (set! human-throttle 0.0)
  (set! human-brake 0.0)
  (set! human-use-item false)
  (set! replay-active false)
  (set! replay-cursor 0)
  (set! simulation-tick 0)
  (set! current-ordinary-thought-ticks ordinary-thought-ticks)
  (set! cadence-pending 0)
  (set! cadence-max-latency-us 0)
  (set! cadence-adaptations 0)
  (set! item-use-count 0)
  (set! hit-count 0)
  (set! contact-count 0)
  (set! contact-cooldowns
        (std-mem/zeroes (az/type [:array racer-count :u8])))
  (set! hazard-spawn-count 0)
  (set! pit-stop-count 0)
  (set! team-radio-count 0)
  (set! accident-count 0)
  (set! team-ai-decision-count 0)
  (set! team-radio-history
        (std-mem/zeroes (az/type [:array (* team-count team-radio-history-per-team) TeamRadioLog])))
  (set! team-radio-heads (std-mem/zeroes (az/type [:array team-count :u8])))
  (set! team-radio-counts (std-mem/zeroes (az/type [:array team-count :u8])))
  (set! race-epoch (+ race-epoch 1))
  (set! language-drivers (std-mem/zeroes (az/type [:array racer-count LanguageDriverState])))
  (set! language-events (std-mem/zeroes (az/type [:array 128 LanguagePlanEvent])))
  (set! language-event-sequence 0)
  (set! language-schedules (std-mem/zeroes (az/type [:array racer-count LanguageSchedule])))
  ;; Keep bounded immutable language exchanges across races: their request
  ;; epochs preserve provenance. Reset never races a UI/history reader.
  (set! entities (std-mem/zeroes (az/type [:array racer-count :u64])))
  (set! hazard-entities (std-mem/zeroes (az/type [:array 32 :u64])))
  (set! team-entities (std-mem/zeroes (az/type [:array team-count :u64])))
  (telemetry/reset!)
  (set! _ (initialize!)))

(az/defn start-replay!
  "Reset the race and install only the previously loaded intent stream."
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
  :-
  :void
  []
  (set! replay-active false))

(az/defn run-replay-parity!
  "Run, capture, reset, and replay one deterministic native race segment."
  :-
  ReplayParityReport
  [[ticks :u32]]
  (clear-replay!)
  ;; The golden parity gate is seed-zero by definition and must not inherit the
  ;; last tournament/game seed or interactive control mode from a long-lived
  ;; development process.
  (set! race-seed 0)
  (set! configured-countdown-ticks 0)
  (set! countdown-ticks 0)
  (set! race-state race-state-running)
  (set! human-controlled false)
  (set! human-steering 0.0)
  (set! human-throttle 0.0)
  (set! human-brake 0.0)
  (set! human-use-item false)
  (set! decision-sequence 0)
  (reset!)
  (step-many! ticks)
  (let [original (state-fingerprint)
        captured (capture-retained-replay!)
        count (az/field captured loaded)
        started (start-replay!)]
    (when started
      (step-many! ticks))
    (let [replayed (state-fingerprint)]
      (ReplayParityReport
       {:valid (and started (> count 0) (ak/== original replayed))
        :intent_count count :ticks ticks
        :original_fingerprint original
        :replay_fingerprint replayed}))))

(az/defn shutdown!
  :-
  :void
  []
  (destroy-dynamics!)
  (when (ak/!= world null)
    (set! _ (flecs/ecs_fini world)))
  (set! world null)
  (set! status-component 0)
  (set! recovery-component 0)
  (set! turnaround-component 0)
  (set! lap-timing-component 0)
  (set! replay-active false)
  (set! initialized false))
