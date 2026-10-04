(ns racing-game.simulation
  "Flecs-owned, fixed-step combat race shared by development and standalone."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as a]
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

(declare brain-pointer vehicle-pointer)

(a/defconst racer-count :usize protocol/racer-count)

(a/defconst team-count :usize protocol/team-count)

(a/defconst team-worker-offset :usize protocol/racer-count)

(a/defconst no-pit-occupant :u8 255)

(a/defconst pit-state-track :u8 0)

(a/defconst pit-state-called :u8 1)

(a/defconst pit-state-servicing :u8 2)

(a/defconst pit-state-exiting :u8 3)

(a/defconst radio-none :u8 0)

(a/defconst radio-tires-wearing :u8 1)

(a/defconst radio-pit-confirmed :u8 2)

(a/defconst radio-box-occupied :u8 3)

(a/defconst radio-boxing-now :u8 4)

(a/defconst radio-fresh-tires :u8 5)

(a/defconst radio-car-damaged :u8 6)

(a/defconst radio-repair-confirmed :u8 7)

(a/defconst radio-car-repaired :u8 8)

(a/defconst radio-stay-out :u8 9)

(a/defconst radio-retired :u8 10)

(a/defconst radio-outside-world :u8 11)

(a/defconst radio-source-none :u8 0)

(a/defconst radio-source-driver :u8 1)

(a/defconst radio-source-strategist :u8 2)

(a/defconst radio-source-race-control :u8 3)

(a/defconst team-action-hold :u8 0)

(a/defconst team-action-driver-a :u8 1)

(a/defconst team-action-driver-b :u8 2)

(a/defconst tire-warning-threshold :f32 0.72)

(a/defconst tire-pit-threshold :f32 0.46)

(a/defconst damage-warning-threshold :f32 0.20)

(a/defconst damage-pit-threshold :f32 0.60)

(a/defconst pit-entry-progress :f32 0.94)

(a/defconst pit-service-seconds :f32 1.25)

(a/defconst pit-repair-extra-seconds :f32 1.75)

(a/defconst team-decision-cadence-ticks :u64 120)

(a/defconst team-radio-history-per-team :usize 32)

(a/defconst hazard-capacity :usize 32)

(a/defconst fixed-step :f32 0.008333333)

(a/defconst ordinary-thought-ticks :u64 40)

(a/defconst pressured-thought-ticks :u64 48)

(a/defconst overloaded-thought-ticks :u64 60)

(a/defconst critical-thought-ticks :u64 80)

(a/defconst ordinary-decision-deadline-ticks :u64 720)

(a/defconst urgent-decision-deadline-ticks :u64 600)

(a/defconst deadline-on-time :u8 0)

(a/defconst deadline-expired :u8 1)

(a/defconst lap-count :u16 3)

(a/defconst checkpoint-count :u8 4)

(a/defconst race-state-countdown :u8 0)

(a/defconst race-state-running :u8 1)

(a/defconst race-state-finished :u8 2)

(a/defconst target-lane-left :u8 0)

(a/defconst target-lane-same :u8 1)

(a/defconst target-lane-right :u8 2)

(a/defconst tactical-status-clear :u8 0)

(a/defconst tactical-status-hazard :u8 1)

(a/defconst tactical-status-stunned :u8 2)

(a/defconst tactical-status-shielded :u8 3)

(a/defconst item-none :u8 0)

(a/defconst item-bolt :u8 1)

(a/defconst item-trap :u8 2)

(a/defconst item-boost :u8 3)

(a/defconst item-shield :u8 4)

(a/defconst item-pulse :u8 5)

(a/defconst item-surge :u8 6)

(a/defconst action-hold :u8 0)

(a/defconst action-use :u8 1)

(a/defconst replay-capacity :usize
  (ak/* racer-count telemetry/entries-per-racer))

(a/defconst replay-file-header-bytes :usize 32)

(a/defconst replay-file-entry-bytes :usize 32)

(a/defconst replay-file-version :u16 2)

(a/defconst replay-file-ok :u32 0)

(a/defconst replay-file-open-failed :u32 1)

(a/defconst replay-file-invalid-size :u32 2)

(a/defconst replay-file-invalid-header :u32 3)

(a/defconst replay-file-incompatible :u32 4)

(a/defconst replay-file-invalid-intent :u32 5)

(a/defstruct Racer
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

(a/defstruct Team
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

(a/defstruct TeamView
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

(a/defstruct TeamRadioLog
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

(a/defstruct RacerBrain
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

(a/defstruct Hazard
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

(a/defstruct HazardView
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

(a/defstruct RacerView
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

(a/defstruct ObservationView
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

(a/defstruct RaceSnapshot
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

(a/defstruct HumanControlSnapshot
  "Inspectable optional reference-driver input. Twenty-AI mode remains the
  default; enabling this takes over racer 0 without changing any other racer."
  {:layout :extern}
  [[:enabled :bool]
   [:steering :f32]
   [:throttle :f32]
   [:brake :f32]
   [:use_item :bool]])

(a/defstruct CadenceSummary
  "Inspectable load-sensitive ordinary thought cadence. The standalone host
  may present live simulation in slow motion while replay remains 120 Hz."
  {:layout :extern}
  [[:ticks :u64]
   [:pending :u8]
   [:adaptations :u64]
   [:max_latency_us :u64]
   [:decisions_per_second :f32]])

(a/defstruct ReplayIntent
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

(a/defstruct ReplaySummary
  "Inspectable state of the bounded native replay stream."
  {:layout :extern}
  [[:active :bool]
   [:loaded :u16]
   [:installed :u16]
   [:remaining :u16]])

(a/defstruct ReplayParityReport
  "Canonical gameplay-state comparison for one captured native intent stream."
  {:layout :extern}
  [[:valid :bool]
   [:intent_count :u16]
   [:ticks :u32]
   [:original_fingerprint :u64]
   [:replay_fingerprint :u64]])

(a/defstruct ReplayFileSummary
  "Strict result for loading one portable recorded-intent fixture."
  {:layout :extern}
  [[:valid :bool]
   [:error_code :u32]
   [:intent_count :u16]
   [:observation_schema :u8]
   [:action_schema :u8]
   [:model_fingerprint :u64]
   [:action_head_fingerprint :u64]])

(a/defvar world [:optional [:* flecs/ecs_world_t]] ak/null)

(a/defvar racer-component :u64 0)

(a/defvar brain-component :u64 0)

(a/defvar hazard-component :u64 0)

(a/defvar team-component :u64 0)

(a/defvar vehicle-component :u64 0)

(a/defvar dynamics-world b3/b3WorldId (std-mem/zeroes (a/type b3/b3WorldId)))

(a/defvar dynamics-surface [:optional [:* b3/b3MeshData]] ak/null)

(a/defvar dynamics-barriers [:optional [:* b3/b3MeshData]] ak/null)

(a/defvar vehicle-controls [:array racer-count driver/Control]
  (std-mem/zeroes (a/type [:array racer-count driver/Control])))

(a/defvar lane-plans [:array racer-count driver/LanePlan]
  (std-mem/zeroes (a/type [:array racer-count driver/LanePlan])))

(a/defvar lap-checkpoints [:array racer-count :u8]
  (std-mem/zeroes (a/type [:array racer-count :u8])))

(a/defvar entities [:array racer-count :u64] (std-mem/zeroes (a/type [:array racer-count :u64])))

(a/defvar hazard-entities [:array 32 :u64]
  (std-mem/zeroes (a/type [:array 32 :u64])))

(a/defvar team-entities [:array team-count :u64]
  (std-mem/zeroes (a/type [:array team-count :u64])))

(a/defvar initialized false)

(a/defvar paused false)

(a/defstruct LanguageDriverState
  "Opt-in ordinary-language control; no body ownership or pose cache."
  {:layout :extern}
  [[:enabled :bool] [:kind :u8] [:epoch :u64] [:revision :u64] [:expires_tick :u64]])

(a/defstruct LanguagePlanEvent
  "Exact accepted/rejected reply, retained independently of the old head log.
  Manual/fixture submissions explicitly have generated=false."
  {:layout :extern}
  [[:valid :bool] [:accepted :bool] [:generated :bool] [:racer :u8]
   [:reason :u8] [:plan protocol/DrivingPlan]
   [:sequence :u64] [:revision :u64] [:epoch :u64]
   [:observed_tick :u64] [:install_tick :u64] [:expires_tick :u64]
   [:byte_count :u16] [:truncated :bool] [:text [:array 2048 :u8]]])

(a/defvar language-drivers [:array racer-count LanguageDriverState]
  (std-mem/zeroes (a/type [:array racer-count LanguageDriverState])))

(a/defvar language-events [:array 128 LanguagePlanEvent]
  (std-mem/zeroes (a/type [:array 128 LanguagePlanEvent])))

(a/defvar language-event-sequence :u64 0)

(a/defstruct LanguageSchedule {:layout :extern}
  [[:revision :u64] [:next_tick :u64] [:marker :u8]])

(a/defvar language-schedules [:array racer-count LanguageSchedule]
  (std-mem/zeroes (a/type [:array racer-count LanguageSchedule])))

(a/defstruct LanguageExchange
  "Exact immutable model input/output and timings, plus installation outcome."
  {:layout :extern}
  [[:valid :bool] [:sequence :u64] [:reason :u8] [:result worker/LanguageResult]])

(a/defvar language-exchanges [:array 128 LanguageExchange]
  (std-mem/zeroes (a/type [:array 128 LanguageExchange])))

(a/defvar language-exchange-sequence :u64 0)

(a/defvar language-exchange-lock :u8 0)

(a/defvar language-mode-requests [:array racer-count :u8]
  (std-mem/zeroes (a/type [:array racer-count :u8])))

(a/defconst language-request-budget-ticks :u64 3600)

(a/defvar simulation-tick :u64 0)

(a/defvar item-use-count :u64 0)

(a/defvar hit-count :u64 0)

(a/defvar contact-count :u64 0)

(a/defvar contact-cooldowns [:array racer-count :u8]
  (std-mem/zeroes (a/type [:array racer-count :u8])))

(a/defvar hazard-spawn-count :u64 0)

(a/defvar pit-stop-count :u64 0)

(a/defvar team-radio-count :u64 0)

(a/defvar accident-count :u64 0)

(a/defvar team-ai-decision-count :u64 0)

(a/defvar team-radio-history [:array (* team-count team-radio-history-per-team) TeamRadioLog]
  (std-mem/zeroes (a/type [:array (* team-count team-radio-history-per-team) TeamRadioLog])))

(a/defvar team-radio-heads [:array team-count :u8]
  (std-mem/zeroes (a/type [:array team-count :u8])))

(a/defvar team-radio-counts [:array team-count :u8]
  (std-mem/zeroes (a/type [:array team-count :u8])))

(a/defvar race-epoch :u64 1)

(a/defvar race-seed :u64 0)

(a/defvar configured-countdown-ticks :u16 0)

(a/defvar countdown-ticks :u16 0)

(a/defvar race-state :u8 race-state-running)

(a/defvar human-controlled false)

(a/defvar human-steering :f32 0.0)

(a/defvar human-throttle :f32 0.0)

(a/defvar human-brake :f32 0.0)

(a/defvar human-use-item false)

(a/defvar items-enabled true)

(a/defvar decision-sequence :u64 0)

(a/defvar current-ordinary-thought-ticks :u64 ordinary-thought-ticks)

(a/defvar cadence-pending :u8 0)

(a/defvar cadence-max-latency-us :u64 0)

(a/defvar cadence-adaptations :u64 0)

(a/defvar replay-intents [:array replay-capacity ReplayIntent]
  (std-mem/zeroes (a/type [:array replay-capacity ReplayIntent])))

(a/defvar replay-count :usize 0)

(a/defvar replay-cursor :usize 0)

(a/defvar replay-active false)

(a/defn next-decision-revision! :u64
  []
  (do
    (ak/= decision-sequence (+ decision-sequence 1))
    decision-sequence))

(a/defn cadence-ticks-for-pressure :u64
  "Pure bounded policy for ordinary thoughts. Urgent requests never use this
  backoff and simulation cadence is unaffected."
  [[pending :u8]
   [max-latency-us :u64]]
  (cond
    (>= max-latency-us 333000) critical-thought-ticks
    (or (>= max-latency-us 250000) (>= pending 7)) overloaded-thought-ticks
    (>= pending 5) pressured-thought-ticks
    :else ordinary-thought-ticks))

(a/defn update-thought-cadence! :void
  []
  (let [workers (worker/summary)
        ^:var max-latency-us (ak/u64 0)]
    (when initialized
      (dotimes [index racer-count]
        (ak/= max-latency-us
              (ak/max max-latency-us
                      (a/field (a/deref (brain-pointer index))
                                average_latency_us)))))
    (let [pending (a/field workers pending)
          desired (cadence-ticks-for-pressure pending max-latency-us)]
      (when (ak/!= desired current-ordinary-thought-ticks)
        (ak/= cadence-adaptations (+ cadence-adaptations 1)))
      (ak/= current-ordinary-thought-ticks desired)
      (ak/= cadence-pending pending)
      (ak/= cadence-max-latency-us max-latency-us))))

(a/defn cadence-summary CadenceSummary
  "Inspect ordinary AI cadence and the pressure that selected it."
  []
  (CadenceSummary
   {:ticks current-ordinary-thought-ticks
    :pending cadence-pending
    :adaptations cadence-adaptations
    :max_latency_us cadence-max-latency-us
    :decisions_per_second
    (/ 120.0
       (ak/as (ak/floatFromInt current-ordinary-thought-ticks) :f32))}))

(a/defn decision-deadline-ticks :u64
  "Return the hard simulation-time budget for one ordinary or urgent thought."
  [[urgent :bool]]
  (if urgent
    urgent-decision-deadline-ticks
    ordinary-decision-deadline-ticks))

(a/defn decision-expired? :bool
  "Pure deadline predicate used by the scheduler and native regression tests."
  [[enqueue-tick :u64]
   [current-tick :u64]
   [urgent :bool]]
  (and (>= current-tick enqueue-tick)
       (>= (- current-tick enqueue-tick)
           (decision-deadline-ticks urgent))))

(a/defn clear-replay! :void
  "Clear the loaded replay without modifying the current race."
  []
  (ak/= replay-active false)
  (ak/= replay-count 0)
  (ak/= replay-cursor 0)
  (ak/= replay-intents
        (std-mem/zeroes (a/type [:array replay-capacity ReplayIntent]))))

(a/defn append-replay-intent! :bool
  "Append one validated, schema-compatible intent in install-tick order."
  [[intent ReplayIntent]]
  (let [ordered
        (or (ak/== replay-count 0)
            (<= (a/field (a/index replay-intents (- replay-count 1))
                          install_tick)
                (a/field intent install_tick)))
        valid
        (and (a/field intent valid)
             (a/field intent accepted)
             (< (a/field intent racer) racer-count)
             (< (a/field intent target) racer-count)
             (<= (a/field intent action) action-use)
             (< replay-count replay-capacity)
             ordered
             (ak/== (a/field intent observation_schema)
                    protocol/observation-schema-version)
             (ak/== (a/field intent action_schema)
                    protocol/action-schema-version)
             (>= (a/field intent lane_target) -0.075)
             (<= (a/field intent lane_target) 0.075)
             (>= (a/field intent target_speed) 0.04)
             (<= (a/field intent target_speed) 0.12))]
    (when valid
      (ak/= (a/index replay-intents replay-count) intent)
      (ak/= replay-count (+ replay-count 1)))
    valid))

(a/defn replay-summary ReplaySummary
  []
  (ReplaySummary
   {:active replay-active
    :loaded (ak/intCast replay-count)
    :installed (ak/intCast replay-cursor)
    :remaining (ak/intCast (- replay-count replay-cursor))}))

(a/defn replay-intent-before :bool
  [[left ReplayIntent]
   [right ReplayIntent]]
  (or (< (a/field left install_tick) (a/field right install_tick))
      (and (ak/== (a/field left install_tick)
                  (a/field right install_tick))
           (or (< (a/field left racer) (a/field right racer))
               (and (ak/== (a/field left racer) (a/field right racer))
                    (< (a/field left revision)
                       (a/field right revision)))))))

(a/defn capture-retained-replay! ReplaySummary
  "Capture every retained accepted decision entirely in native memory and
  order it by install tick, racer, and revision for deterministic playback."
  []
  (clear-replay!)
  (dotimes [racer-index racer-count]
    (let [racer-id (ak/as (ak/intCast racer-index) :u8)
          count (ak/as (ak/intCast
                        (ak/min (telemetry/decision-count racer-id)
                                telemetry/entries-per-racer))
                       :usize)]
      (dotimes [offset count]
        (when (< replay-count replay-capacity)
          (let [entry (telemetry/entry-at racer-id offset)]
            (when (and (a/field entry valid)
                       (a/field entry accepted)
                       (ak/== (a/field entry observation_schema)
                              protocol/observation-schema-version)
                       (ak/== (a/field entry action_schema)
                              protocol/action-schema-version))
              (ak/= (a/index replay-intents replay-count)
                    (ReplayIntent
                     {:valid true :accepted true
                      :urgent (a/field entry urgent)
                      :racer (a/field entry racer_id)
                      :rank (a/field entry rank) :lap (a/field entry lap)
                      :item (a/field entry item) :action (a/field entry action)
                      :target (a/field entry target)
                      :observation_schema (a/field entry observation_schema)
                      :action_schema (a/field entry action_schema)
                      :reserved 0 :revision (a/field entry revision)
                      :install_tick (a/field entry install_tick)
                      :lane_target (a/field entry lane_target)
                      :target_speed (a/field entry target_speed)}))
              (ak/= replay-count (+ replay-count 1))))))))
  (let [^:var index (ak/usize 1)]
    (ak/while (< index replay-count)
      (let [key (a/index replay-intents index)
            ^:var cursor (ak/usize index)]
        (ak/while (and (> cursor 0)
                       (replay-intent-before
                        key (a/index replay-intents (- cursor 1))))
          (ak/= (a/index replay-intents cursor)
                (a/index replay-intents (- cursor 1)))
          (ak/= cursor (- cursor 1)))
        (ak/= (a/index replay-intents cursor) key))
      (ak/= index (+ index 1))))
  (replay-summary))

(a/defn replay-read-u16 :u16
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as (a/index bytes offset) :u16)
     (ak/<< (ak/as (a/index bytes (+ offset 1)) :u16) 8)))

(a/defn replay-read-u32 :u32
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as (a/index bytes offset) :u32)
     (ak/<< (ak/as (a/index bytes (+ offset 1)) :u32) 8)
     (ak/<< (ak/as (a/index bytes (+ offset 2)) :u32) 16)
     (ak/<< (ak/as (a/index bytes (+ offset 3)) :u32) 24)))

(a/defn replay-read-u64 :u64
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (+ (ak/as (replay-read-u32 bytes offset) :u64)
     (ak/<< (ak/as (replay-read-u32 bytes (+ offset 4)) :u64) 32)))

(a/defn replay-read-f32 :f32
  [[bytes [:c-pointer :u8]]
   [offset :usize]]
  (let [bits (ak/u32 (replay-read-u32 bytes offset))
        value (ak/f32 (ak/bitCast bits))]
    value))

(a/defn replay-file-summary ReplayFileSummary
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

(a/defn load-replay-file! ReplayFileSummary
  "Load one canonical little-endian replay artifact with strict schema,
  provenance, size, ordering, and intent validation."
  [[path [:* {:size :c :const? true} :u8]]]
  (let [file (runtime/fopen path "rb")]
    (if (ak/== file ak/null)
      (replay-file-summary false replay-file-open-failed 0 0 0 0 0)
      (do
        (ak/= :_ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (ak/= :_ (runtime/fseek file 0 0))
          (if (or (< signed-size (ak/as (ak/intCast replay-file-header-bytes) :isize))
                  (> signed-size
                     (ak/as (ak/intCast
                             (ak/+ replay-file-header-bytes
                                   (ak/* replay-capacity replay-file-entry-bytes)))
                            :isize)))
            (do
              (ak/= :_ (runtime/fclose file))
              (replay-file-summary false replay-file-invalid-size 0 0 0 0 0))
            (let [size (ak/as (ak/intCast signed-size) :usize)
                  allocation (runtime/malloc size)]
              (if (ak/== allocation ak/null)
                (do
                  (ak/= :_ (runtime/fclose file))
                  (replay-file-summary false replay-file-invalid-size
                                       0 0 0 0 0))
                (let [bytes (a/cast allocation [:c-pointer :u8])
                      read-count (runtime/fread bytes 1 size file)
                      ^:var result
                      (ak/as (replay-file-summary false replay-file-invalid-size
                                           0 0 0 0 0) ReplayFileSummary)]
                  (ak/= :_ (runtime/fclose file))
                  (when (ak/== read-count size)
                    (let [magic-valid
                          (and (ak/== (a/index bytes 0) 65)
                               (ak/== (a/index bytes 1) 71)
                               (ak/== (a/index bytes 2) 82)
                               (ak/== (a/index bytes 3) 80)
                               (ak/== (a/index bytes 4) 76)
                               (ak/== (a/index bytes 5) 89)
                               (ak/== (a/index bytes 6) 48)
                               (ak/== (a/index bytes 7) 49))
                          version (replay-read-u16 bytes 8)
                          count (replay-read-u16 bytes 10)
                          observation-schema (a/index bytes 12)
                          action-schema (a/index bytes 13)
                          model-fingerprint (replay-read-u64 bytes 16)
                          action-head-fingerprint (replay-read-u64 bytes 24)
                          expected-size
                          (+ replay-file-header-bytes
                             (* (ak/as count :usize) replay-file-entry-bytes))
                          compatible
                          (and (ak/== (a/index bytes 14) racer-count)
                               (ak/== (a/index bytes 15) team-count)
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
                        (ak/= result
                              (replay-file-summary
                               false replay-file-invalid-header count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        (or (ak/== count 0)
                            (> count replay-capacity)
                            (ak/!= size expected-size))
                        (ak/= result
                              (replay-file-summary
                               false replay-file-invalid-size count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        (ak/! compatible)
                        (ak/= result
                              (replay-file-summary
                               false replay-file-incompatible count
                               observation-schema action-schema
                               model-fingerprint action-head-fingerprint))

                        :else
                        (do
                          (clear-replay!)
                          (let [^:var all-valid (ak/bool true)]
                            (dotimes [index (ak/as count :usize)]
                              (when all-valid
                                (let [base (+ replay-file-header-bytes
                                              (* index replay-file-entry-bytes))
                                      intent
                                      (ReplayIntent
                                       {:valid true :accepted true
                                        :racer (a/index bytes base)
                                        :rank (a/index bytes (+ base 1))
                                        :lap (replay-read-u16 bytes (+ base 2))
                                        :item (a/index bytes (+ base 4))
                                        :action (a/index bytes (+ base 5))
                                        :target (a/index bytes (+ base 6))
                                        :urgent (ak/!= (a/index bytes (+ base 7)) 0)
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
                                    (ak/= all-valid false)))))
                            (if all-valid
                              (ak/= result
                                    (replay-file-summary
                                     true replay-file-ok count
                                     observation-schema action-schema
                                     model-fingerprint action-head-fingerprint))
                              (do
                                (clear-replay!)
                                (ak/= result
                                      (replay-file-summary
                                       false replay-file-invalid-intent count
                                       observation-schema action-schema
                                       model-fingerprint
                                       action-head-fingerprint)))))))))
                  (runtime/free allocation)
                  result)))))))))

(a/defn register-component :u64
  [[flecs-world [:* flecs/ecs_world_t]]
   [component-name [:* {:size :c :const? true} :u8]]
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

(a/defn racer-pointer [:* Racer]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               racer-component (ak/sizeOf Racer))
      (a/cast [:* Racer])))

(a/defn brain-pointer [:* RacerBrain]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               brain-component (ak/sizeOf RacerBrain))
      (a/cast [:* RacerBrain])))

(a/defn team-pointer [:* Team]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index team-entities index)
                               team-component (ak/sizeOf Team))
      (a/cast [:* Team])))

(a/defvar status-component :u64 0)

(a/defvar recovery-component :u64 0)

(a/defvar turnaround-component :u64 0)

(a/defn turnaround-pointer [:* turnaround/Output] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               turnaround-component (ak/sizeOf turnaround/Output))
      (a/cast [:* turnaround/Output])))

(a/defn ensure-turnaround! :void
  "Attach independent sparse turnaround state on the frame thread. Existing
  chassis, wheel and driver allocations remain untouched." []
  (when (ak/== turnaround-component 0)
    (ak/= turnaround-component
          (register-component (a/unwrap world) "RacingTurnaround"
                              (ak/sizeOf turnaround/Output) (ak/alignOf turnaround/Output)))
    (flecs/ecs_add_id world turnaround-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [value (std-mem/zeroes (a/type turnaround/Output))]
        (flecs/ecs_set_id world (a/index entities i) turnaround-component
                          (ak/sizeOf turnaround/Output) (ak/& value))))))

(a/defn turnaround-view turnaround/Output
  "Inspect native body-relative recovery without changing driver intent." [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= turnaround-component 0))
    (a/deref (turnaround-pointer index))
    (std-mem/zeroes (a/type turnaround/Output))))

(a/defvar lap-timing-component :u64 0)

(a/defn lap-timing-pointer [:* lap-timing/Entry] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               lap-timing-component (ak/sizeOf lap-timing/Entry))
      (a/cast [:* lap-timing/Entry])))

(a/defn ensure-lap-timing! :void
  "Attach timing on the frame thread without recreating live vehicles." []
  (when (ak/== lap-timing-component 0)
    (ak/= lap-timing-component
          (register-component (a/unwrap world) "RacingLapTiming"
                              (ak/sizeOf lap-timing/Entry) (ak/alignOf lap-timing/Entry)))
    (flecs/ecs_add_id world lap-timing-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [^:var value (std-mem/zeroes (a/type lap-timing/Entry))]
        (ak/= (a/field value complete_start)
              (or (ak/== simulation-tick 0) (> countdown-ticks 0)))
        (flecs/ecs_set_id world (a/index entities i) lap-timing-component
                          (ak/sizeOf lap-timing/Entry) (ak/& value))))))

(a/defn lap-timing-view lap-timing/Entry
  "Measured 120Hz ticks, with an explicit flag for a mid-lap live attachment." [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= lap-timing-component 0))
    (a/deref (lap-timing-pointer index))
    (std-mem/zeroes (a/type lap-timing/Entry))))

(a/defn recovery-pointer [:* recovery/Output] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               recovery-component (ak/sizeOf recovery/Output))
      (a/cast [:* recovery/Output])))

(a/defn ensure-recovery! :void
  "Frame-thread addition of independent sparse manoeuvring state. Does not
  recreate vehicles or move the running world's existing component storage." []
  (when (ak/== recovery-component 0)
    (ak/= recovery-component
          (register-component (a/unwrap world) "RacingRecovery"
                              (ak/sizeOf recovery/Output) (ak/alignOf recovery/Output)))
    (flecs/ecs_add_id world recovery-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [value (std-mem/zeroes (a/type recovery/Output))]
        (flecs/ecs_set_id world (a/index entities i) recovery-component
                          (ak/sizeOf recovery/Output) (ak/& value))))))

(a/defn recovery-view recovery/Output
  "Inspect actual manoeuvring state, gear and pedals without altering the race." [[index :usize]]
  (if (and initialized (< index racer-count) (ak/!= recovery-component 0))
    (a/deref (recovery-pointer index))
    (std-mem/zeroes (a/type recovery/Output))))

(a/defn status-pointer [:* status/Entry] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               status-component (ak/sizeOf status/Entry))
      (a/cast [:* status/Entry])))

(a/defn ensure-race-status! :void
  "Add a new sparse component to the existing world; ordinary reload keeps it." []
  (when (ak/== status-component 0)
    (ak/= status-component
          (register-component (a/unwrap world) "RacingClassification"
                              (ak/sizeOf status/Entry) (ak/alignOf status/Entry)))
    (flecs/ecs_add_id world status-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [entry (std-mem/zeroes (a/type status/Entry))]
        (flecs/ecs_set_id world (a/index entities i) status-component
                          (ak/sizeOf status/Entry) (ak/& entry))))))

(a/defn retired? :bool
  "DNF is not finished: no lap or finish tick is manufactured." [[index :usize]]
  (and (< index racer-count) (ak/!= status-component 0)
       (a/field (a/deref (status-pointer index)) retired)))

(a/defn retirement-view status/Entry
  "Inspect retirement reason, frozen classification and its actual race tick." [[index :usize]]
  (if (and (< index racer-count) (ak/!= status-component 0))
    (a/deref (status-pointer index))
    (std-mem/zeroes (a/type status/Entry))))

(a/defn retired-count :u8 []
  (let [^:var count (ak/u8 0)]
    (dotimes [i racer-count]
      (when (retired? i) (ak/= count (+ count 1))))
    count))

(a/defn teammate-id :u8
  "Return the other driver in one of the four fixed two-driver teams."
  [[identifier :u8]]
  (if (ak/== (mod identifier 2) 0)
    (+ identifier 1)
    (- identifier 1)))

(a/defn radio-message! :void
  "Publish one bounded semantic message and retain it in the team's native
  newest-first history. Source identifies driver or strategist direction."
  [[index :usize]
   [source :u8]
   [code :u8]]
  (let [racer (racer-pointer index)
        team-index (ak/as (ak/intCast (a/field (a/deref racer) team))
                          :usize)
        team (team-pointer team-index)
        head (ak/as (ak/intCast (a/index team-radio-heads team-index))
                    :usize)
        destination (+ (* team-index team-radio-history-per-team) head)]
    (ak/= (a/field (a/deref team) radio_sequence)
          (+ (a/field (a/deref team) radio_sequence) 1))
    (ak/= (a/field (a/deref team) radio_code) code)
    (ak/= (a/field (a/deref team) radio_target)
          (a/field (a/deref racer) id))
    (ak/= (a/field (a/deref racer) radio_code) code)
    (ak/= (a/field (a/deref racer) radio_source) source)
    (ak/= (a/field (a/deref racer) radio_revision)
          (a/field (a/deref team) radio_sequence))
    (ak/= (a/index team-radio-history destination)
          (TeamRadioLog
           {:valid true
            :team (a/field (a/deref team) id)
            :source source
            :target (a/field (a/deref racer) id)
            :code code
            :pit_state (a/field (a/deref racer) pit_state)
            :instruction (a/field (a/deref team) instruction)
            :reserved 0
            :model_accepted false
            :model_action 0
            :prompt_byte_count 0
            :input_token_count 0
            :best_token 0
            :tick simulation-tick
            :decision_revision (a/field (a/deref team) decision_revision)
            :latency_us (a/field (a/deref team) last_latency_us)
            :tokens_per_second 0.0
            :tire_condition (a/field (a/deref racer) tire_condition)
            :damage (a/field (a/deref racer) damage)
            :prompt_bytes
            (std-mem/zeroes (a/type [:array 160 :u8]))}))
    (ak/= (a/index team-radio-heads team-index)
          (ak/intCast (mod (+ head 1) team-radio-history-per-team)))
    (ak/= (a/index team-radio-counts team-index)
          (ak/intCast
           (ak/min team-radio-history-per-team
                   (+ (ak/as (ak/intCast
                              (a/index team-radio-counts team-index))
                             :usize)
                      1))))
    (ak/= team-radio-count (+ team-radio-count 1))))

(a/defn attach-team-model-decision! :void
  "Attach the completed strategist inference to the radio message it caused."
  [[team-index :usize]
   [result worker/InferenceResult]]
  (let [head (ak/as (ak/intCast (a/index team-radio-heads team-index))
                    :usize)
        slot (if (ak/== head 0)
               (- team-radio-history-per-team 1)
               (- head 1))
        destination (+ (* team-index team-radio-history-per-team) slot)
        ^:var entry (a/index team-radio-history destination)
        prompt-count
        (ak/min worker/prompt-capacity
                (ak/as (ak/intCast (a/field result prompt_byte_count))
                       :usize))]
    (when (and (a/field entry valid)
               (ak/== (a/field entry source) radio-source-strategist)
               (ak/== (a/field entry decision_revision)
                      (a/field result revision)))
      (ak/= (a/field entry model_accepted) (a/field result accepted))
      (ak/= (a/field entry model_action) (a/field result action_code))
      (ak/= (a/field entry prompt_byte_count) (ak/intCast prompt-count))
      (ak/= (a/field entry input_token_count)
            (a/field result input_token_count))
      (ak/= (a/field entry best_token) (a/field result best_token))
      (ak/= (a/field entry tokens_per_second)
            (a/field result tokens_per_second))
      (dotimes [position prompt-count]
        (ak/= (a/index (a/field entry prompt_bytes) position)
              (a/index (a/field result prompt_bytes) position)))
      (ak/= (a/index team-radio-history destination) entry))))

(a/defn team-radio-history-count :u8
  "Return the retained radio-message count for one team."
  [[team-id :u8]]
  (if (< team-id team-count)
    (a/index team-radio-counts (ak/intCast team-id))
    0))

(a/defn team-radio-entry TeamRadioLog
  "Inspect one newest-first semantic radio exchange for one team."
  [[team-id :u8]
   [offset :usize]]
  (if (or (>= team-id team-count)
          (>= offset
              (ak/as (ak/intCast
                      (a/index team-radio-counts (ak/intCast team-id)))
                     :usize)))
    (std-mem/zeroes (a/type TeamRadioLog))
    (let [team-index (ak/as (ak/intCast team-id) :usize)
          head (ak/as (ak/intCast (a/index team-radio-heads team-index))
                      :usize)
          distance (+ offset 1)
          slot (if (>= head distance)
                 (- head distance)
                 (- (+ team-radio-history-per-team head) distance))]
      (a/index team-radio-history
                (+ (* team-index team-radio-history-per-team) slot)))))

(a/defn team-view TeamView
  "Inspect one of the four Flecs-owned teams."
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
        :driver_a (a/field (a/deref team) driver_a)
        :driver_b (a/field (a/deref team) driver_b)
        :pit_occupant (a/field (a/deref team) pit_occupant)
        :instruction (a/field (a/deref team) instruction)
        :radio_code (a/field (a/deref team) radio_code)
        :radio_target (a/field (a/deref team) radio_target)
        :pending (a/field (a/deref team) pending)
        :pit_stops (a/field (a/deref team) pit_stops)
        :decision_revision (a/field (a/deref team) decision_revision)
        :decisions (a/field (a/deref team) decisions)
        :invalid_decisions (a/field (a/deref team) invalid_decisions)
        :last_latency_us (a/field (a/deref team) last_latency_us)
        :average_latency_us (a/field (a/deref team) average_latency_us)
        :radio_sequence (a/field (a/deref team) radio_sequence)}))))

(a/defn install-replay-intents! :void
  "Install every recorded intent due on this exact fixed simulation tick."
  []
  (ak/while
   (and replay-active
        (< replay-cursor replay-count)
        (<= (a/field (a/index replay-intents replay-cursor) install_tick)
            simulation-tick))
    (let [intent (a/index replay-intents replay-cursor)
          racer-index (ak/as (ak/intCast (a/field intent racer)) :usize)
          racer (racer-pointer racer-index)
          brain (brain-pointer racer-index)]
      (ak/= (a/field (a/deref brain) lane_target)
            (a/field intent lane_target))
      (ak/= (a/field (a/deref brain) target_speed)
            (a/field intent target_speed))
      (ak/= (a/field (a/deref brain) target) (a/field intent target))
      (ak/= (a/field (a/deref brain) item_action) (a/field intent action))
      (ak/= (a/field (a/deref brain) source) telemetry/source-replay)
      (ak/= (a/field (a/deref brain) urgent) false)
      (ak/= (a/field (a/deref brain) pending) false)
      (ak/= (a/field (a/deref brain) pending_urgent) false)
      (ak/= (a/field (a/deref brain) pending_target)
            (a/field intent racer))
      (ak/= (a/field (a/deref brain) pending_revision) 0)
      (ak/= (a/field (a/deref brain) pending_tick) 0)
      (ak/= (a/field (a/deref brain) last_decision_tick) simulation-tick)
      (ak/= (a/field (a/deref brain) decision_revision)
            (a/field intent revision))
      (ak/= (a/field (a/deref brain) decisions)
            (+ (a/field (a/deref brain) decisions) 1))
      (when (a/field intent urgent)
        (ak/= (a/field (a/deref brain) urgent_decisions)
              (+ (a/field (a/deref brain) urgent_decisions) 1)))
      (ak/= (a/field (a/deref brain) next_decision_tick)
            (+ simulation-tick ordinary-thought-ticks))
      (telemetry/record!
       (telemetry/DecisionLog
        {:valid true :racer_id (a/field intent racer)
         :source telemetry/source-replay :accepted true
         :urgent (a/field intent urgent)
         :prompt_truncated false :response_truncated false
         :rank (a/field intent rank) :item (a/field intent item)
         :target (a/field intent target) :action (a/field intent action)
         :observation_schema (a/field intent observation_schema)
         :action_schema (a/field intent action_schema)
         :validation_code 0 :deadline_status 0 :lap (a/field intent lap)
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
         :sampler_state 0 :revision (a/field intent revision)
         :race_epoch race-epoch
         :enqueue_tick (a/field intent install_tick)
         :install_tick simulation-tick
         :simulation_tick (a/field intent install_tick)
         :queue_us 0 :prefill_us 0 :decode_us 0 :total_us 0
         :tokens_per_second 0.0
         :progress (a/field (a/deref racer) progress)
         :speed (a/field (a/deref racer) speed)
         :lane_target (a/field intent lane_target)
         :target_speed (a/field intent target_speed)
         :input_tokens (std-mem/zeroes (a/type [:array 64 :u32]))
         :output_tokens (std-mem/zeroes (a/type [:array 16 :u32]))
         :prompt_bytes (std-mem/zeroes (a/type [:array 384 :u8]))
         :response_bytes (std-mem/zeroes (a/type [:array 96 :u8]))}))
      (ak/= replay-cursor (+ replay-cursor 1)))))

(a/defn hazard-pointer [:* Hazard]
  [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index hazard-entities index)
                               hazard-component (ak/sizeOf Hazard))
      (a/cast [:* Hazard])))

(a/defn wrapped-distance :f32
  [[a :f32]
   [b :f32]]
  (let [distance (ak/abs (- a b))]
    (ak/min distance (- 1.0 distance))))

(a/defn racers-overlap? :bool
  "Pure circle/contact predicate in track coordinates. Absolute progress keeps
  racers on adjacent laps physically close across the finish line while never
  colliding racers separated by a full lap."
  [[absolute-progress-a :f32]
   [lane-a :f32]
   [absolute-progress-b :f32]
   [lane-b :f32]]
  (and (< (ak/abs (- absolute-progress-a absolute-progress-b)) 0.018)
       (< (ak/abs (- lane-a lane-b)) 0.058)))

(a/defn contact-axis-overlap? :bool
  "Separating-axis test for a 5.8m long, 2.6m wide car, in km world units." [[dx :f32] [dy :f32] [axis :f32] [heading-a :f32] [heading-b :f32]]
  (let [distance (ak/abs (+ (* dx (std-math/cos axis)) (* dy (std-math/sin axis))))
        a (- heading-a axis) b (- heading-b axis)
        radius-a (+ (* 0.0029 (ak/abs (std-math/cos a)))
                    (* 0.0013 (ak/abs (std-math/sin a))))
        radius-b (+ (* 0.0029 (ak/abs (std-math/cos b)))
                    (* 0.0013 (ak/abs (std-math/sin b))))]
    (< distance (+ radius-a radius-b 0.0001))))

(a/defn racer-contours-overlap? :bool
  "Oriented physical car bounds, not the old screen-space circle radius." [[a [:* Racer]] [b [:* Racer]]]
  (let [dx (- (a/field (a/deref a) x) (a/field (a/deref b) x))
        dy (- (a/field (a/deref a) y) (a/field (a/deref b) y))
        ha (a/field (a/deref a) heading) hb (a/field (a/deref b) heading)]
    (and (contact-axis-overlap? dx dy ha ha hb)
         (contact-axis-overlap? dx dy (+ ha 1.5707963) ha hb)
         (contact-axis-overlap? dx dy hb ha hb)
         (contact-axis-overlap? dx dy (+ hb 1.5707963) ha hb))))

(a/defn hit-racer! :bool
  "Apply visible counterplay and immediately schedule a fresh thought for the
  struck racer. Shields absorb exactly one hit."
  [[target-index :usize]
   [stun-seconds :f32]]
  (let [target (racer-pointer target-index)
        target-brain (brain-pointer target-index)
        ^:var landed (ak/bool false)]
    (if (a/field (a/deref target) shielded)
      (ak/= (a/field (a/deref target) shielded) false)
      (do
        (ak/= (a/field (a/deref target) stun_seconds)
              (ak/max (a/field (a/deref target) stun_seconds)
                      stun-seconds))
        (ak/= hit-count (+ hit-count 1))
        (ak/= landed true)))
    (ak/= (a/field (a/deref target-brain) urgent) true)
    (ak/= (a/field (a/deref target-brain) next_decision_tick)
          simulation-tick)
    landed))

(a/defn spawn-hazard! :bool
  "Activate one preallocated Flecs combat object without allocating."
  [[owner-index :usize]
   [kind :u8]
   [target :u8]]
  (let [owner (racer-pointer owner-index)
        owner-brain (brain-pointer owner-index)
        ^:var spawned (ak/bool false)]
    (dotimes [slot hazard-capacity]
      (when (ak/! spawned)
        (let [hazard (hazard-pointer slot)]
          (when (ak/! (a/field (a/deref hazard) active))
            (ak/= (a/field (a/deref hazard) active) true)
            (ak/= (a/field (a/deref hazard) kind) kind)
            (ak/= (a/field (a/deref hazard) owner) (ak/intCast owner-index))
            (ak/= (a/field (a/deref hazard) target) target)
            (ak/= (a/field (a/deref hazard) progress)
                  (a/field (a/deref owner) progress))
            (ak/= (a/field (a/deref hazard) lane)
                  (a/field (a/deref owner) lane))
            (ak/= (a/field (a/deref hazard) speed)
                  (if (ak/== kind item-bolt) 0.34 0.0))
            (ak/= (a/field (a/deref hazard) arming_seconds)
                  (if (ak/== kind item-bolt) 0.08 0.25))
            (ak/= (a/field (a/deref hazard) ttl)
                  (if (ak/== kind item-bolt) 2.4 8.0))
            (ak/= (a/field (a/deref hazard) decision_revision)
                  (a/field (a/deref owner-brain) decision_revision))
            (ak/= hazard-spawn-count (+ hazard-spawn-count 1))
            (ak/= spawned true)))))
    spawned))

(a/defn step-hazards! :void
  "Move pooled bolts and resolve bolt/trap contact after all racers advance."
  []
  (dotimes [slot hazard-capacity]
    (let [hazard (hazard-pointer slot)]
      (when (a/field (a/deref hazard) active)
        (ak/= (a/field (a/deref hazard) ttl)
              (- (a/field (a/deref hazard) ttl) fixed-step))
        (ak/= (a/field (a/deref hazard) arming_seconds)
              (ak/max 0.0
                      (- (a/field (a/deref hazard) arming_seconds)
                         fixed-step)))
        (when (ak/== (a/field (a/deref hazard) kind) item-bolt)
          (ak/= (a/field (a/deref hazard) progress)
                (mod (+ (a/field (a/deref hazard) progress)
                        (track/progress-step (a/field (a/deref hazard) speed) fixed-step))
                     1.0)))
        (dotimes [racer-index racer-count]
          (when (and (a/field (a/deref hazard) active)
                     (<= (a/field (a/deref hazard) arming_seconds) 0.0)
                     (ak/!= racer-index
                            (ak/as (ak/intCast
                                    (a/field (a/deref hazard) owner))
                                   :usize)))
            (let [racer (racer-pointer racer-index)
                  progress-distance
                  (wrapped-distance (a/field (a/deref hazard) progress)
                                    (a/field (a/deref racer) progress))
                  lane-distance
                  (ak/abs (- (a/field (a/deref hazard) lane)
                             (a/field (a/deref racer) lane)))
                  progress-radius
                  (ak/f32 (if (ak/== (a/field (a/deref hazard) kind) item-bolt)
                    0.012
                    0.009))]
              (when (and (< progress-distance progress-radius)
                         (< lane-distance 0.045))
                (when (hit-racer! racer-index
                                  (if (ak/== (a/field (a/deref hazard) kind)
                                            item-trap)
                                    0.85
                                    0.55))
                  (ak/= :_
                        (telemetry/mark-hit!
                         (a/field (a/deref hazard) owner)
                         (a/field (a/deref hazard) decision_revision))))
                (ak/= (a/field (a/deref hazard) active) false)))))
        (when (<= (a/field (a/deref hazard) ttl) 0.0)
          (ak/= (a/field (a/deref hazard) active) false))))))

(a/defn update-position! :void
  [[racer [:* Racer]]]
  (let [progress (a/field (a/deref racer) progress)
        lane (a/field (a/deref racer) lane)
        state (a/field (a/deref racer) pit_state)
        sample (if (or (and (ak/== state pit-state-called) (>= progress pit-entry-progress))
                       (ak/== state pit-state-servicing) (ak/== state pit-state-exiting))
                 (track/pit-pose progress lane)
                 (track/pose progress lane))]
    (ak/= (a/field (a/deref racer) x) (a/field sample x))
    (ak/= (a/field (a/deref racer) y) (a/field sample y))
    (ak/= (a/field (a/deref racer) heading) (a/field sample heading))))

(a/defn absolute-progress :f32
  [[racer [:* Racer]]]
  (+ (ak/as (ak/floatFromInt (a/field (a/deref racer) lap)) :f32)
     (a/field (a/deref racer) progress)))

(a/defn checkpoint-for-progress :u8
  "Return the last legal quarter-lap checkpoint crossed, from 0 through 3.
  Progress is authoritative and can only advance, so checkpoint order cannot
  be skipped by steering or by a malformed external control value."
  [[progress :f32]]
  (cond
    (>= progress 0.75) 3
    (>= progress 0.50) 2
    (>= progress 0.25) 1
    :else 0))

(a/defn complete-lap! :void
  "Advance exactly one lap and permanently record this racer's finish tick."
  [[racer [:* Racer]]]
  (ak/= (a/field (a/deref racer) lap)
        (+ (a/field (a/deref racer) lap) 1))
  (when (and (ak/! (a/field (a/deref racer) finished))
             (>= (a/field (a/deref racer) lap) lap-count))
    (ak/= (a/field (a/deref racer) finished) true)
    (ak/= (a/field (a/deref racer) finish_tick) simulation-tick)))

(a/defn advance-racer-progress! :void
  "Advance along the legal track direction and cross the finish line at most
  once. Negative or invalid reverse movement cannot manufacture a lap."
  [[racer [:* Racer]]
   [distance :f32]]
  (let [advanced (+ (a/field (a/deref racer) progress)
                    (ak/max distance 0.0))]
    (if (>= advanced 1.0)
      (do
        (ak/= (a/field (a/deref racer) progress) (- advanced 1.0))
        (complete-lap! racer))
      (ak/= (a/field (a/deref racer) progress) advanced))))

(a/defn choose-target :u8
  "Nearest physical car ahead, independent of lap/classification. A lapped,
  finished or retired body still occupies the track and must remain visible
  to the model. This does not schedule decisions for a retired driver."
  [[self-index :usize]]
  (let [self (racer-pointer self-index)
        ^:var chosen (ak/u8 (a/field (a/deref self) id))
        ^:var best-distance (ak/f32 1000.0)]
    (dotimes [other-index racer-count]
      (when (ak/!= other-index self-index)
        (let [other (racer-pointer other-index)
              distance (mod (- (a/field (a/deref other) progress)
                               (a/field (a/deref self) progress)) 1.0)]
          (when (and (> distance 0.0)
                     (< distance best-distance))
            (ak/= best-distance distance)
            (ak/= chosen (a/field (a/deref other) id))))))
    chosen))

(a/defn target-distance-bin :u8
  "Quantize only the selected visible opponent's forward distance. Nine means
  no opponent ahead; lower values are progressively closer."
  [[self-index :usize]
   [target :u8]]
  (let [self (racer-pointer self-index)]
    (if (ak/== target (a/field (a/deref self) id))
      9
      (let [other (racer-pointer (ak/as (ak/intCast target) :usize))
            distance
            (mod (- (a/field (a/deref other) progress)
                    (a/field (a/deref self) progress)) 1.0)]
        (ak/as (ak/intFromFloat (ak/min 9.0 (* distance 100.0)))
               :u8)))))

(a/defn target-lane-relation :u8
  "Describe the selected opponent as left, same-lane, or right of the racer."
  [[self-index :usize]
   [target :u8]]
  (let [self (racer-pointer self-index)]
    (if (ak/== target (a/field (a/deref self) id))
      target-lane-same
      (let [other (racer-pointer (ak/as (ak/intCast target) :usize))
            delta (- (a/field (a/deref other) lane)
                     (a/field (a/deref self) lane))]
        (cond
          (< delta -0.020) target-lane-left
          (> delta 0.020) target-lane-right
          :else target-lane-same)))))

(a/defn classify-tactical-status :u8
  "Summarize immediate control constraints before passive protection. A shield
  does not make a blocked lane clear; a stunned driver must first recover."
  [[stunned :bool] [hazard-near :bool] [shielded :bool]]
  (cond
    stunned tactical-status-stunned
    hazard-near tactical-status-hazard
    shielded tactical-status-shielded
    :else tactical-status-clear))

(a/defn racer-tactical-status :u8
  "Expose local hazards, including stopped/wrecked cars in the driven lane.
  Physical obstacles do not disappear when their driver is classified DNF."
  [[index :usize]]
  (let [racer (racer-pointer index)
        ^:var hazard-near (ak/bool false)]
    (dotimes [slot hazard-capacity]
      (let [hazard (hazard-pointer slot)]
        (when (and (a/field (a/deref hazard) active)
                   (ak/!= (ak/as (ak/intCast
                                  (a/field (a/deref hazard) owner))
                                 :usize)
                          index)
                   (< (wrapped-distance
                       (a/field (a/deref hazard) progress)
                       (a/field (a/deref racer) progress))
                      0.060)
                   (< (ak/abs (- (a/field (a/deref hazard) lane)
                                 (a/field (a/deref racer) lane)))
                      0.055))
          (ak/= hazard-near true))))
    (dotimes [other-index racer-count]
      (when (ak/!= index other-index)
        (let [other (racer-pointer other-index)
              ahead (mod (- (a/field (a/deref other) progress)
                             (a/field (a/deref racer) progress)) 1.0)]
          (when (and (> ahead 0.0) (< ahead 0.02)
                     (< (a/field (a/deref other) speed) 0.002)
                     (< (ak/abs (- (a/field (a/deref other) lane)
                                   (a/field (a/deref racer) lane))) 0.055))
            (ak/= hazard-near true)))))
    (classify-tactical-status
      (> (a/field (a/deref racer) stun_seconds) 0.0)
      hazard-near
      (a/field (a/deref racer) shielded))))

(a/defn build-observation ObservationView
  "Build the single authoritative immutable worker observation."
  [[index :usize]
   [urgent :bool]]
  (if (or (ak/! initialized) (>= index racer-count))
    (std-mem/zeroes (a/type ObservationView))
    (let [racer (racer-pointer index)
          brain (brain-pointer index)
          aggression (a/field (a/deref brain) aggression)
          persona (cond
                    (< aggression 0.48) (ak/as 0 :u8)
                    (< aggression 0.72) (ak/as 1 :u8)
                    :else (ak/as 2 :u8))
          target (choose-target index)]
      (ObservationView
       {:valid true
        :racer (a/field (a/deref racer) id)
        :rank (a/field (a/deref racer) rank)
        :target target
        :persona persona
        :item (a/field (a/deref racer) item)
        :target_distance (target-distance-bin index target)
        :target_lane (target-lane-relation index target)
        :tactical_status (racer-tactical-status index)
        :urgent urgent
        :lap (a/field (a/deref racer) lap)
        :progress (a/field (a/deref racer) progress)
        :speed (a/field (a/deref racer) speed)}))))

(a/defn current-observation ObservationView
  "Inspect exactly what the next native decision for one racer can see."
  [[index :usize]]
  (if (or (ak/! initialized) (>= index racer-count))
    (std-mem/zeroes (a/type ObservationView))
    (build-observation index
                       (a/field (a/deref (brain-pointer index)) urgent))))

(a/defn make-decision! :void
  "Install one independent tactical intent. This transparent policy is the
  native fallback and training baseline used whenever LLM output is late."
  [[index :usize]
   [urgent :bool]
   [deadline-status :u8]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        aggression (a/field (a/deref brain) aggression)
        risk (a/field (a/deref brain) risk)
        ;; Publication revisions remain monotonic across hot resets. Tactical
        ;; behavior must use reset-local state so the same seed reproduces the
        ;; same physical race independently of prior REPL activity.
        lane-phase (mod (+ (a/field (a/deref brain) decisions)
                           (ak/as (ak/intCast index) :u64))
                        3)
        lane-target (ak/f32 (cond
                      (ak/== lane-phase 0) -0.075
                      (ak/== lane-phase 1) 0.0
                      :else 0.075))
        target-speed (+ 0.068 (* aggression 0.010) (* risk 0.006))
        target (choose-target index)
        revision (next-decision-revision!)
        should-use (and (ak/!= (a/field (a/deref racer) item) item-none)
                        (or urgent
                            (> aggression 0.62)
                            (ak/== (a/field (a/deref racer) item) item-boost)))]
    (ak/= (a/field (a/deref brain) lane_target) lane-target)
    (ak/= (a/field (a/deref brain) target_speed) target-speed)
    (ak/= (a/field (a/deref brain) target) target)
    (ak/= (a/field (a/deref brain) item_action)
          (if should-use action-use action-hold))
    (ak/= (a/field (a/deref brain) source) telemetry/source-fallback)
    ;; Urgency is an edge-triggered request. Consuming the decision clears it;
    ;; a later native event can set it again without turning every thought into
    ;; an urgent one.
    (ak/= (a/field (a/deref brain) urgent) false)
    (ak/= (a/field (a/deref brain) pending) false)
    (ak/= (a/field (a/deref brain) pending_urgent) false)
    (ak/= (a/field (a/deref brain) pending_target)
          (a/field (a/deref racer) id))
    (ak/= (a/field (a/deref brain) pending_revision) 0)
    (ak/= (a/field (a/deref brain) pending_tick) 0)
    (ak/= (a/field (a/deref brain) decision_revision) revision)
    (ak/= (a/field (a/deref brain) last_decision_tick) simulation-tick)
    (ak/= (a/field (a/deref brain) decisions)
          (+ (a/field (a/deref brain) decisions) 1))
    (when urgent
      (ak/= (a/field (a/deref brain) urgent_decisions)
            (+ (a/field (a/deref brain) urgent_decisions) 1)))
    (ak/= (a/field (a/deref brain) next_decision_tick)
          (+ simulation-tick current-ordinary-thought-ticks))
    (telemetry/record-fallback!
     (a/field (a/deref racer) id)
     (a/field (a/deref racer) rank)
     (a/field (a/deref racer) lap)
     (a/field (a/deref racer) item)
     target
     (a/field (a/deref brain) item_action)
     urgent
     deadline-status
     revision
     race-epoch
     simulation-tick
     (a/field (a/deref racer) progress)
     (a/field (a/deref racer) speed)
     lane-target
     target-speed)))

(a/defn record-worker-result! :void
  [[result worker/InferenceResult]
   [accepted :bool]
   [item-action :u8]
   [deadline-status :u8]]
  (let [base
        (telemetry/DecisionLog
         {:valid true
          :racer_id (a/field result racer)
          :source telemetry/source-llm
          :accepted accepted
          :urgent (a/field result urgent)
          :prompt_truncated false
          :response_truncated false
          :rank (a/field result rank)
          :item (a/field result item)
          :target (a/field result target)
          :action item-action
          :observation_schema (a/field result observation_schema)
          :action_schema (a/field result action_schema)
          :validation_code (if (> deadline-status 0)
                             2
                             (if accepted 0 1))
          :deadline_status deadline-status
          :lap (a/field result lap)
          :input_token_count (a/field result input_token_count)
          :output_token_count (a/field result output_token_count)
          :prompt_byte_count (a/field result prompt_byte_count)
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
          :sampler_state (a/field result sampler_state)
          :revision (a/field result revision)
          :race_epoch (a/field result race_epoch)
          :enqueue_tick (a/field result simulation_tick)
          :install_tick simulation-tick
          :simulation_tick (a/field result simulation_tick)
          :queue_us (a/field result queue_us)
          :prefill_us (a/field result prefill_us)
          :decode_us (a/field result decode_us)
          :total_us (a/field result total_us)
          :tokens_per_second (a/field result tokens_per_second)
          :progress (a/field result progress)
          :speed (a/field result speed)
          :lane_target (a/field result lane_target)
          :target_speed (a/field result target_speed)
          :input_tokens (std-mem/zeroes (a/type [:array 64 :u32]))
          :output_tokens (std-mem/zeroes (a/type [:array 16 :u32]))
          :prompt_bytes (std-mem/zeroes (a/type [:array 384 :u8]))
          :response_bytes (std-mem/zeroes (a/type [:array 96 :u8]))})]
    (telemetry/record-llm!
     base
     (a/slice (a/field result prompt_bytes)
               0 (a/field result prompt_byte_count))
     (a/slice (a/field result response_bytes)
               0 (a/field result output_token_count))
     (a/slice (a/field result input_tokens)
               0 (ak/min 32 (a/field result input_token_count)))
     (a/slice (a/field result output_tokens)
               0 (ak/min 1 (a/field result output_token_count))))))

(a/defn valid-worker-action? :bool
  "Validate the complete constrained action envelope before it can affect the
  live race. The target must be the exact bounded opponent selected in the
  immutable observation, not merely an in-range racer id."
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

(a/defn install-worker-result! :void
  "Install only an on-time result for the current race epoch and outstanding
  revision. Late results remain observable but never replace the safe intent."
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        result (worker/result-for index
                                  (a/field (a/deref brain) decision_revision))]
    (when (and (a/field result valid)
               (ak/== (a/field result race_epoch) race-epoch)
               (ak/== (a/field result observation_schema)
                      protocol/observation-schema-version)
               (ak/== (a/field result action_schema)
                      protocol/action-schema-version)
               (a/field (a/deref brain) pending)
               (ak/== (a/field result revision)
                      (a/field (a/deref brain) pending_revision)))
      (let [expired
            (decision-expired? (a/field result simulation_tick)
                               simulation-tick
                               (a/field result urgent))
            semantically-accepted
            (valid-worker-action?
             (a/field result accepted)
             (a/field result action_code)
             (a/field result item_action)
             (a/field result target)
             (a/field (a/deref brain) pending_target)
             (a/field result lane_target)
             (a/field result target_speed)
             (a/field result output_token_count)
             (a/field result best_token))
            accepted (and semantically-accepted (ak/! expired))
            item-action
            (if (and accepted
                     (ak/!= (a/field (a/deref racer) item) item-none)
                     (ak/== (a/field result item_action) action-use))
              action-use
              action-hold)]
        (when accepted
          (ak/= (a/field (a/deref brain) lane_target)
                (a/field result lane_target))
          (ak/= (a/field (a/deref brain) target_speed)
                (a/field result target_speed))
          (ak/= (a/field (a/deref brain) target) (a/field result target))
          (ak/= (a/field (a/deref brain) item_action) item-action)
          (ak/= (a/field (a/deref brain) source) telemetry/source-llm)
          (ak/= (a/field (a/deref brain) last_decision_tick)
                simulation-tick))
        (when (and (ak/! semantically-accepted) (ak/! expired))
          (ak/= (a/field (a/deref brain) invalid_decisions)
                (+ (a/field (a/deref brain) invalid_decisions) 1)))
        (when expired
          (ak/= (a/field (a/deref brain) deadline_misses)
                (+ (a/field (a/deref brain) deadline_misses) 1))
          (ak/= (a/field (a/deref brain) urgent) true))
        (ak/= (a/field (a/deref brain) pending) false)
        (ak/= (a/field (a/deref brain) pending_urgent) false)
        (ak/= (a/field (a/deref brain) pending_target)
              (a/field (a/deref racer) id))
        (ak/= (a/field (a/deref brain) pending_revision) 0)
        (ak/= (a/field (a/deref brain) pending_tick) 0)
        (ak/= (a/field (a/deref brain) decision_revision)
              (a/field result revision))
        (ak/= (a/field (a/deref brain) decisions)
              (+ (a/field (a/deref brain) decisions) 1))
        (when (a/field result urgent)
          (ak/= (a/field (a/deref brain) urgent_decisions)
                (+ (a/field (a/deref brain) urgent_decisions) 1)))
        (ak/= (a/field (a/deref brain) last_latency_us)
              (a/field result total_us))
        (ak/= (a/field (a/deref brain) average_latency_us)
              (if (ak/== (a/field (a/deref brain) decisions) 1)
                (a/field result total_us)
                (/ (+ (a/field (a/deref brain) average_latency_us)
                      (a/field result total_us))
                   2)))
        (ak/= (a/field (a/deref brain) next_decision_tick)
              (if (or (ak/! accepted)
                      (a/field (a/deref brain) urgent))
                simulation-tick
                (+ (a/field result simulation_tick)
                   current-ordinary-thought-ticks)))
        (record-worker-result! result accepted item-action
                               (if expired
                                 deadline-expired
                                 deadline-on-time))))))

(a/defn submit-worker-request! :bool
  [[index :usize]
   [urgent :bool]]
  (let [brain (brain-pointer index)
        revision (next-decision-revision!)
        observation (build-observation index urgent)
        request
        (worker/InferenceRequest
         {:valid true
          :actor_kind worker/actor-kind-driver
          :team (a/field (a/deref (racer-pointer index)) team)
          :racer (a/field observation racer)
          :rank (a/field observation rank)
          :lap (a/field observation lap)
          :item (a/field observation item)
          :target (a/field observation target)
          :persona (a/field observation persona)
          :target_distance (a/field observation target_distance)
          :target_lane (a/field observation target_lane)
          :tactical_status (a/field observation tactical_status)
          :driver_a 0 :driver_b 0 :rank_a 0 :rank_b 0
          :tire_a 0 :tire_b 0 :damage_a 0 :damage_b 0
          :pit_a 0 :pit_b 0 :box_occupied false
          :urgent (a/field observation urgent)
          :observation_schema protocol/observation-schema-version
          :action_schema protocol/action-schema-version
          :revision revision
          :race_epoch race-epoch
          :simulation_tick simulation-tick
          :progress (a/field observation progress)
          :speed (a/field observation speed)
          :enqueue_seconds 0.0})]
    (if (worker/submit! request)
      (do
        (ak/= (a/field (a/deref brain) pending) true)
        (ak/= (a/field (a/deref brain) pending_urgent) urgent)
        (ak/= (a/field (a/deref brain) pending_target)
              (a/field observation target))
        (ak/= (a/field (a/deref brain) pending_revision) revision)
        (ak/= (a/field (a/deref brain) pending_tick) simulation-tick)
        ;; Only consume the urgency captured by this immutable request. A new
        ;; hit or pickup can set the edge again while inference is in flight.
        (ak/= (a/field (a/deref brain) urgent) false)
        (ak/= (a/field (a/deref brain) next_decision_tick)
              (+ simulation-tick current-ordinary-thought-ticks))
        true)
      false)))

(a/defn apply-item! :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        item (a/field (a/deref racer) item)]
    (when (and (ak/!= item item-none)
               (ak/== (a/field (a/deref brain) item_action) action-use))
      (let [^:var used (ak/bool true)]
        (cond
          (ak/== item item-boost)
          (ak/= (a/field (a/deref racer) boost_seconds) 1.25)

          (ak/== item item-shield)
          (ak/= (a/field (a/deref racer) shielded) true)

          (or (ak/== item item-bolt) (ak/== item item-trap))
          (ak/= used
                (spawn-hazard! index item
                               (a/field (a/deref brain) target)))

          (ak/== item item-pulse)
          (dotimes [target-index racer-count]
            (when (ak/!= target-index index)
              (let [target (racer-pointer target-index)]
                (when (< (wrapped-distance
                          (a/field (a/deref racer) progress)
                          (a/field (a/deref target) progress))
                         0.10)
                  (when (hit-racer! target-index 0.35)
                    (ak/= :_
                          (telemetry/mark-hit!
                           (a/field (a/deref racer) id)
                           (a/field (a/deref brain)
                                     decision_revision))))))))

          (ak/== item item-surge)
          (ak/= (a/field (a/deref racer) boost_seconds) 3.0)

          :else
          (ak/= used false))
        (when used
          (ak/= :_
                (telemetry/mark-item-used!
                 (a/field (a/deref racer) id)
                 (a/field (a/deref brain) decision_revision)))
          (ak/= (a/field (a/deref racer) item) item-none)
          (ak/= (a/field (a/deref brain) item_action) action-hold)
          (ak/= item-use-count (+ item-use-count 1)))))))

(a/defn collect-item! :void
  [[index :usize]]
  (let [racer (racer-pointer index)]
    (when (and items-enabled
               (ak/== (a/field (a/deref racer) item) item-none)
               (<= (a/field (a/deref racer) pickup_cooldown) 0.0)
               (< (wrapped-distance (a/field (a/deref racer) progress) 0.25)
                  0.008))
      (ak/= (a/field (a/deref racer) item)
            (+ 1 (ak/as (ak/intCast
                         (mod (+ (ak/as (ak/intCast index) :u64)
                                 (ak/as (ak/intCast
                                         (a/field (a/deref racer) lap))
                                        :u64)
                                 race-seed)
                              6))
                        :u8)))
      (ak/= (a/field (a/deref racer) pickup_cooldown) 1.0)
      (let [brain (brain-pointer index)]
        (ak/= (a/field (a/deref brain) next_decision_tick) simulation-tick)
        (ak/= (a/field (a/deref brain) urgent) true)))))

(a/defn apply-human-control! :void
  "Translate the optional reference driver's normalized input into the same
  bounded RacerBrain intent fields consumed by the native vehicle controller."
  []
  (let [brain (brain-pointer 0)
        steering (ak/min 1.0 (ak/max -1.0 human-steering))
        throttle (ak/min 1.0 (ak/max 0.0 human-throttle))
        brake (ak/min 1.0 (ak/max 0.0 human-brake))
        lane-target
        (ak/min 0.075
                (ak/max -0.075
                        (+ (a/field (a/deref brain) lane_target)
                           (* steering fixed-step 0.72))))
        target-speed
        (ak/min 0.12
                (ak/max 0.015
                        (- (+ 0.020 (* throttle 0.100))
                           (* brake 0.080))))]
    (ak/= (a/field (a/deref brain) lane_target) lane-target)
    (ak/= (a/field (a/deref brain) target_speed) target-speed)
    (ak/= (a/field (a/deref brain) target) (choose-target 0))
    (ak/= (a/field (a/deref brain) item_action)
          (if human-use-item action-use action-hold))
    (ak/= (a/field (a/deref brain) source) telemetry/source-human)
    (ak/= (a/field (a/deref brain) pending) false)
    (ak/= (a/field (a/deref brain) pending_urgent) false)
    (ak/= (a/field (a/deref brain) pending_target) 0)
    (ak/= (a/field (a/deref brain) pending_revision) 0)
    (ak/= (a/field (a/deref brain) pending_tick) 0)
    (ak/= (a/field (a/deref brain) last_decision_tick) simulation-tick)
    (ak/= (a/field (a/deref brain) urgent) false)))

(a/defn expire-pending-decision! :bool
  "Replace an over-budget in-flight thought with a new deterministic safe
  intent. The worker may finish later, but its older revision cannot install."
  [[index :usize]]
  (let [brain (brain-pointer index)
        expired
        (and (a/field (a/deref brain) pending)
             (decision-expired?
              (a/field (a/deref brain) pending_tick)
              simulation-tick
              (a/field (a/deref brain) pending_urgent)))]
    (when expired
      (ak/= (a/field (a/deref brain) deadline_misses)
            (+ (a/field (a/deref brain) deadline_misses) 1))
      (make-decision! index true deadline-expired))
    expired))

(a/defn update-tire-strategy! :void
  "Consume tire-contact work and report the first warning to the strategist.
  Waiting and unexecuted lane intentions cause no wear; a pit call does not
  suspend wear while driving to the box. Pit selection belongs to the team AI."
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        loss (physics/take-vehicle-tread-loss! (a/deref (vehicle-pointer index)))]
    (when (and (ak/!= (a/field (a/deref racer) pit_state)
                       pit-state-servicing)
               (ak/! (a/field (a/deref racer) finished)))
      (ak/= (a/field (a/deref racer) tire_condition)
            (ak/max 0.0 (- (a/field (a/deref racer) tire_condition) loss)))
      (when (and (ak/== (a/field (a/deref racer) tire_stage) 0)
                 (<= (a/field (a/deref racer) tire_condition)
                     tire-warning-threshold))
        (ak/= (a/field (a/deref racer) tire_stage) 1)
        (radio-message! index radio-source-driver radio-tires-wearing)
        (ak/= (a/field (a/deref brain) urgent) true)
        (ak/= (a/field (a/deref brain) next_decision_tick)
              simulation-tick)))))

(a/defn driver-needs-pit? :bool
  "Return whether current tires or persistent collision damage justify a
  strategist pit call. Finished or already-called cars are not candidates."
  [[racer [:* Racer]]]
  (and (ak/! (a/field (a/deref racer) finished))
       (ak/! (retired? (a/field (a/deref racer) id)))
       (ak/== (a/field (a/deref racer) pit_state) pit-state-track)
       (or (<= (a/field (a/deref racer) tire_condition)
               tire-pit-threshold)
           (>= (a/field (a/deref racer) damage)
               damage-pit-threshold))))

(a/defn driver-service-urgency :f32
  [[racer [:* Racer]]]
  (+ (- 1.0 (a/field (a/deref racer) tire_condition))
     (* 1.35 (a/field (a/deref racer) damage))))

(a/defn team-priority-driver :u8
  "Choose the teammate with the strongest current service need for the safety
  fallback and for the strategist's bounded observation target."
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a (a/field (a/deref team) driver_a)
        driver-b (a/field (a/deref team) driver_b)
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

(a/defn request-driver-pit! :bool
  "Reserve one team's real pit box and publish the strategist's instruction."
  [[team-index :usize]
   [driver-id :u8]
   [voluntary :bool]]
  (let [team (team-pointer team-index)
        racer (racer-pointer (ak/intCast driver-id))
        brain (brain-pointer (ak/intCast driver-id))
        free (ak/== (a/field (a/deref team) pit_occupant)
                    no-pit-occupant)
        candidate (and (ak/! (a/field (a/deref racer) finished))
                       (ak/! (retired? driver-id))
                       (ak/== (a/field (a/deref racer) pit_state) pit-state-track)
                       (or voluntary (ak/! (a/field (a/index language-drivers driver-id) enabled)))
                       (or voluntary (driver-needs-pit? racer)))]
    (if (and free candidate)
      (do
        (ak/= (a/field (a/deref team) pit_occupant) driver-id)
        (ak/= (a/field (a/deref team) instruction)
              (if (ak/== driver-id (a/field (a/deref team) driver_a))
                team-action-driver-a
                team-action-driver-b))
        (ak/= (a/field (a/deref racer) pit_state) pit-state-called)
        (ak/= (a/field (a/deref racer) pit_waiting) false)
        (ak/= (a/field (a/deref racer) tire_stage) 2)
        (radio-message!
         (ak/intCast driver-id) radio-source-strategist
         (if (>= (a/field (a/deref racer) damage) damage-pit-threshold)
           radio-repair-confirmed
           radio-pit-confirmed))
        (ak/= (a/field (a/deref brain) urgent) true)
        (ak/= (a/field (a/deref brain) next_decision_tick) simulation-tick)
        true)
      (do
        (when (and candidate
                   (ak/!= (a/field (a/deref team) pit_occupant) driver-id)
                   (ak/! (a/field (a/deref racer) pit_waiting)))
          (ak/= (a/field (a/deref racer) pit_waiting) true)
          (radio-message! (ak/intCast driver-id) radio-source-strategist
                          radio-box-occupied))
        false))))

(a/defn call-driver-to-pit! :bool
  "Existing automatic tire/damage policy; voluntary language calls use the
  same physical pit workflow with an explicit optional-service request." [[team-index :usize] [driver-id :u8]]
  (request-driver-pit! team-index driver-id false))

(a/defn install-team-worker-result! :void
  "Validate and install one team strategist's independent LLM decision. The
  model chooses hold/driver A/driver B; current box ownership and damage/tire
  state remain authoritative safety constraints."
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        actor-index (+ team-worker-offset team-index)
        result (worker/result-for actor-index
                                  (a/field (a/deref team) decision_revision))]
    (when (and (a/field (a/deref team) pending)
               (a/field result valid)
               (ak/== (a/field result revision)
                      (a/field (a/deref team) pending_revision))
               (ak/== (a/field result race_epoch) race-epoch))
      (let [model-action (a/field result action_code)
            model-driver
            (cond
              (ak/== model-action team-action-driver-a)
              (a/field (a/deref team) driver_a)

              (ak/== model-action team-action-driver-b)
              (a/field (a/deref team) driver_b)

              :else no-pit-occupant)
            priority (team-priority-driver team-index)
            priority-racer (racer-pointer (ak/intCast priority))
            emergency (or (< (a/field (a/deref priority-racer) tire_condition)
                             0.16)
                          (> (a/field (a/deref priority-racer) damage) 0.82))
            model-valid (and (a/field result accepted)
                             (ak/== (a/field result actor_kind)
                                    worker/actor-kind-team)
                             (ak/== (a/field result team) team-index)
                             (< (a/field result action_code) 3)
                             (ak/== (a/field result output_token_count) 1)
                             (ak/== (a/field result best_token)
                                    (+ 32 (a/field result action_code))))
            selected
            (if (and model-valid
                     (ak/!= model-driver no-pit-occupant)
                     (driver-needs-pit?
                      (racer-pointer (ak/intCast model-driver))))
              model-driver
              (if emergency priority no-pit-occupant))]
        (ak/= (a/field (a/deref team) pending) false)
        (ak/= (a/field (a/deref team) pending_revision) 0)
        (ak/= (a/field (a/deref team) decision_revision)
              (a/field result revision))
        (ak/= (a/field (a/deref team) decisions)
              (+ (a/field (a/deref team) decisions) 1))
        (ak/= team-ai-decision-count (+ team-ai-decision-count 1))
        (ak/= (a/field (a/deref team) last_latency_us)
              (a/field result total_us))
        (ak/= (a/field (a/deref team) average_latency_us)
              (if (ak/== (a/field (a/deref team) decisions) 1)
                (a/field result total_us)
                (/ (+ (a/field (a/deref team) average_latency_us)
                      (a/field result total_us))
                   2)))
        (when (ak/! model-valid)
          (ak/= (a/field (a/deref team) invalid_decisions)
                (+ (a/field (a/deref team) invalid_decisions) 1)))
        (if (ak/!= selected no-pit-occupant)
          (ak/= :_ (call-driver-to-pit! team-index selected))
          (let [target (team-priority-driver team-index)]
            (ak/= (a/field (a/deref team) instruction) team-action-hold)
            (radio-message! (ak/intCast target) radio-source-strategist
                            radio-stay-out)))
        (attach-team-model-decision! team-index result)
        (ak/= (a/field (a/deref team) next_decision_tick)
              (+ simulation-tick team-decision-cadence-ticks))))))

(a/defn submit-team-worker-request! :bool
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a-id (a/field (a/deref team) driver_a)
        driver-b-id (a/field (a/deref team) driver_b)
        driver-a (racer-pointer (ak/intCast driver-a-id))
        driver-b (racer-pointer (ak/intCast driver-b-id))
        revision (next-decision-revision!)
        actor-index (+ team-worker-offset team-index)
        tire-a
        (ak/as (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (a/field (a/deref driver-a)
                                             tire_condition)))))
               :u8)
        tire-b
        (ak/as (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (a/field (a/deref driver-b)
                                             tire_condition)))))
               :u8)
        damage-a
        (ak/as (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (a/field (a/deref driver-a) damage)))))
               :u8)
        damage-b
        (ak/as (ak/intFromFloat
                (ak/min 100.0
                        (* 100.0
                           (ak/max 0.0
                                   (a/field (a/deref driver-b) damage)))))
               :u8)
        request
        (worker/InferenceRequest
         {:valid true
          :actor_kind worker/actor-kind-team
          :team (ak/intCast team-index)
          :racer (ak/intCast actor-index)
          :rank (a/field (a/deref driver-a) rank)
          :lap (a/field (a/deref driver-a) lap)
          :item (if (ak/== (a/field (a/deref team) pit_occupant)
                           no-pit-occupant) 0 1)
          :target driver-a-id
          :persona 0 :target_distance 0 :target_lane 1 :tactical_status 0
          :driver_a driver-a-id
          :driver_b driver-b-id
          :rank_a (a/field (a/deref driver-a) rank)
          :rank_b (a/field (a/deref driver-b) rank)
          :tire_a tire-a :tire_b tire-b
          :damage_a damage-a :damage_b damage-b
          :pit_a (if (retired? driver-a-id) 4 (a/field (a/deref driver-a) pit_state))
          :pit_b (if (retired? driver-b-id) 4 (a/field (a/deref driver-b) pit_state))
          :box_occupied
          (ak/!= (a/field (a/deref team) pit_occupant) no-pit-occupant)
          :urgent true
          :observation_schema protocol/observation-schema-version
          :action_schema protocol/action-schema-version
          :revision revision
          :race_epoch race-epoch
          :simulation_tick simulation-tick
          :progress (a/field (a/deref driver-a) progress)
          :speed (a/field (a/deref driver-a) speed)
          :enqueue_seconds 0.0})]
    (if (worker/submit! request)
      (do
        (ak/= (a/field (a/deref team) pending) true)
        (ak/= (a/field (a/deref team) pending_revision) revision)
        (ak/= (a/field (a/deref team) pending_tick) simulation-tick)
        true)
      false)))

(a/defn step-team-strategist! :void
  "Advance one independent team AI actor after both drivers have updated."
  [[team-index :usize]]
  (let [team (team-pointer team-index)
        driver-a (racer-pointer
                  (ak/intCast (a/field (a/deref team) driver_a)))
        driver-b (racer-pointer
                  (ak/intCast (a/field (a/deref team) driver_b)))
        needs-decision (or (driver-needs-pit? driver-a)
                           (driver-needs-pit? driver-b))]
    (install-team-worker-result! team-index)
    (when (and needs-decision
               (ak/! (a/field (a/deref team) pending))
               (ak/== (a/field (a/deref team) pit_occupant)
                      no-pit-occupant)
               (>= simulation-tick
                   (a/field (a/deref team) next_decision_tick)))
      (when (ak/! (submit-team-worker-request! team-index))
        (let [priority (team-priority-driver team-index)]
          (ak/= :_ (call-driver-to-pit! team-index priority))
          (ak/= (a/field (a/deref team) next_decision_tick)
                (+ simulation-tick team-decision-cadence-ticks)))))))

(a/defn pit-box-progress :f32
  [[team-id :u8]]
  ;; Ten 12.9m-spaced boxes remain within the fully widened pit apron.
  (+ 0.968 (* (ak/as (ak/floatFromInt team-id) :f32) 0.003)))

(a/defn vehicle-pointer [:* physics/Vehicle] [[index :usize]]
  (-> world
      (flecs/ecs_get_sparse_id (a/index entities index)
                               vehicle-component (ak/sizeOf physics/Vehicle))
      (a/cast [:* physics/Vehicle])))

(a/defn ensure-dynamics! :void
  "Frame-thread ownership. Ordinary reload preserves the world and sparse bodies." []
  (when (ak/== dynamics-surface ak/null)
    (ak/= lane-plans (std-mem/zeroes (a/type [:array racer-count driver/LanePlan])))
    (ak/= dynamics-world (physics/create-world -9.81))
    (ak/= dynamics-surface (terrain/create! dynamics-world))
    (ak/= vehicle-component
          (register-component (a/unwrap world) "RacingPhysicalVehicle"
                              (ak/sizeOf physics/Vehicle) (ak/alignOf physics/Vehicle)))
    (flecs/ecs_add_id world vehicle-component flecs/EcsSparse)
    (dotimes [i racer-count]
      (let [racer (racer-pointer i)
            progress (a/field (a/deref racer) progress)
            p (circuit/at-distance (* progress 4309.0)
                                   (* (a/field (a/deref racer) lane) 50.0))
            car (physics/create-vehicle dynamics-world
                  (b3/b3Pos {:x (a/field p x) :y (a/field p y)
                             :z (+ (a/field p z) 0.76)})
                  (a/field p heading))
            tag (ak/as (ak/ptrFromInt (+ i 1)) [:optional [:* :anyopaque]])]
        (b3/b3Body_SetUserData (a/field car chassis) tag)
        (dotimes [wheel 4]
          (b3/b3Body_SetUserData (a/index (a/field car wheels) wheel) tag))
        (flecs/ecs_set_id world (a/index entities i) vehicle-component
                          (ak/sizeOf physics/Vehicle) (ak/& car))
        (ak/= (a/index lap-checkpoints i) (checkpoint-for-progress progress)))))
  ;; Added once on the frame thread, including to an already-running world.
  ;; Existing vehicles and the road mesh are not recreated or repositioned.
  (when (ak/== dynamics-barriers ak/null)
    (ak/= dynamics-barriers (barriers/create! dynamics-world))))

(a/defn destroy-dynamics! :void []
  (when (ak/!= dynamics-surface ak/null)
    (physics/destroy-world! dynamics-world)
    (when (ak/!= dynamics-barriers ak/null)
      (barriers/destroy! (a/unwrap dynamics-barriers))
      (ak/= dynamics-barriers ak/null))
    (terrain/destroy! (a/unwrap dynamics-surface))
    (ak/= dynamics-surface ak/null)))

(a/defn vehicle-pose physics/BodyState
  "Actual metre-space rigid pose: part 0 chassis, parts 1..4 individual wheels." [[index :usize] [part :usize]]
  (let [car (a/deref (vehicle-pointer index))]
    (physics/body-state (if (ak/== part 0) (a/field car chassis)
                           (a/index (a/field car wheels) (- part 1))))))

(a/defn enable-language-driving! :bool
  "Opt into readable plans on the simulation thread. Start held, invalidate
  pending encoded decisions, and preserve every physical body and race result." [[index :usize] [enabled :bool]]
  (when (or (>= index racer-count) (ak/! initialized)) (ak/return false))
  (let [brain (brain-pointer index)]
    (ak/= (a/index language-drivers index)
      (LanguageDriverState {:enabled enabled :kind protocol/plan-hold
                            :epoch race-epoch
                            :revision (a/field (a/index language-drivers index) revision)
                            :expires_tick 0}))
    (ak/= (a/field (a/deref brain) pending) false)
    (ak/= (a/field (a/deref brain) target_speed) 0.0)
    (ak/= (a/field (a/deref brain) item_action) action-hold))
  true)

(a/defn language-lane-clear? :bool
  "Read-only, short-horizon clearance of a proposed lateral transition.
  Positive offset is LEFT of the road heading. Sampled prediction is not a
  collision guarantee: continuous traffic braking and Box3D remain active." [[index :usize] [lane-metres :f32]]
  (let [body (vehicle-pose index 0)
        projected (track/project (* (a/field body x) 0.001) (* (a/field body y) 0.001))
        speed (ak/sqrt (+ (* (a/field body vx) (a/field body vx))
                          (* (a/field body vy) (a/field body vy))))
        distance (ak/max 8.0 (* speed 1.5))]
    (dotimes [sample-index 16]
      (let [fraction (/ (ak/as (ak/floatFromInt (+ sample-index 1)) :f32) 16.0)
            seconds (* fraction 1.5)
            blend (* fraction fraction (- 3.0 (* 2.0 fraction)))
            lane (+ (* 50.0 (a/field projected lane) (- 1.0 blend)) (* lane-metres blend))
            point (circuit/at-distance
                    (+ (* (a/field projected progress) 4309.0) (* distance fraction)) lane)
            x (a/field point x) y (a/field point y) yaw (a/field point heading)]
        (when (ak/! (turnaround/static-clearance dynamics-world x y
                       (+ (a/field point z) 0.76) yaw)) (ak/return false))
        (dotimes [other-index racer-count]
          (when (ak/!= other-index index)
            (let [^:var other (vehicle-pose other-index 0)
                  before (turnaround/footprint-separation (a/field body x)
                           (a/field body y) (turnaround/heading body) other)]
              (ak/= (a/field other x) (+ (a/field other x) (* (a/field other vx) seconds)))
              (ak/= (a/field other y) (+ (a/field other y) (* (a/field other vy) seconds)))
              (when (ak/! (turnaround/separation-safe? before
                             (turnaround/footprint-separation x y yaw other)))
                (ak/return false))))))))
  true)

(a/defn driving-observation protocol/DrivingObservation
  "Read ONLY on the simulation owner thread. Uses current Box3D poses and
  physical route queries, not the old coarse/head observation or cached UI." [[index :usize]]
  (when (or (>= index racer-count) (ak/! initialized))
    (ak/return (std-mem/zeroes (a/type protocol/DrivingObservation))))
  (let [body (vehicle-pose index 0)
        racer (a/deref (racer-pointer index))
        projected (track/project (* (a/field body x) 0.001) (* (a/field body y) 0.001))
        lane (* 50.0 (a/field projected lane))
        route (circuit/at-distance (* 4309.0 (a/field projected progress)) 0.0)
        team (a/deref (team-pointer (ak/intCast (a/field racer team))))
        active (and (ak/== (a/field racer pit_state) pit-state-track)
                    (ak/! (retired? index)) (ak/! (a/field racer finished))
                    (ak/== race-state race-state-running))]
    (protocol/DrivingObservation
      {:valid true :racer (ak/intCast index)
       :speed_kmh (* 3.6 (ak/sqrt (+ (* (a/field body vx) (a/field body vx))
                                     (* (a/field body vy) (a/field body vy)))))
       :off_track (> (ak/abs lane) 6.5)
       :wrong_way (< (std-math/cos (- (turnaround/heading body) (a/field route heading))) 0.0)
       :overturned (driver/overturned? body)
       :ahead_clear (language-lane-clear? index lane)
       :left_clear (language-lane-clear? index 3.75)
       :right_clear (language-lane-clear? index -3.75)
       :tire_percent (* 100.0 (a/field racer tire_condition))
       :damage_percent (* 100.0 (a/field racer damage))
       :pit_available (and active (ak/== (a/field racer pit_state) pit-state-track)
                           (ak/== (a/field team pit_occupant) no-pit-occupant))
       :active active})))

(a/defn language-driving-request worker/LanguageRequest
  "Build the exact bounded text handed to inference and retained in history.
  Invalid or overflowing observations are rejected, never silently clipped." [[index :usize] [revision :u64]]
  (let [observation (driving-observation index)
        ^:var request (std-mem/zeroes (a/type worker/LanguageRequest))
        system "Choose: hold (stop), follow, pass left, pass right, pit, yield (slow). Avoid blocked routes. Reply with one command. Optional Radio: your message."
        length (protocol/describe-driving-observation observation
                 (ak/& (a/index (a/field request prompt_bytes) 0)) 160)]
    (when (or (ak/! (a/field observation active)) (ak/== revision 0) (ak/== length 0))
      (ak/return request))
    (ak/= (a/field request valid) true)
    (ak/= (a/field request actor) (ak/intCast index))
    (ak/= (a/field request revision) revision)
    (ak/= (a/field request epoch) race-epoch)
    (ak/= (a/field request observed_tick) simulation-tick)
    (ak/= (a/field request expires_tick) (+ simulation-tick language-request-budget-ticks))
    (ak/= (a/field request prompt_byte_count) (ak/intCast length))
    (ak/= (a/field request system_byte_count) (ak/intCast (a/field system len)))
    (dotimes [i (a/field system len)]
      (ak/= (a/index (a/field request system_bytes) i) (a/index system i)))
    request))

(a/defn install-language-plan! :u8
  "Simulation-thread handoff, never a worker-thread body mutation. Envelope
  identity/time comes from the request, not text invented by the model.
  Every bounded reply is retained verbatim, including rejected replies."
  [[index :usize] [epoch :u64] [revision :u64] [observed :u64] [expires :u64]
   [bytes [:* {:size :c :const? true} :u8]] [length :usize] [complete :bool] [generated :bool]]
  (when (or (>= index racer-count) (ak/! initialized))
    (ak/return protocol/plan-inactive-driver))
  (let [state (ak/& (a/index language-drivers index))
        racer (racer-pointer index) brain (brain-pointer index)
        team-index (ak/as (ak/intCast (a/field (a/deref racer) team)) :usize)
        team (team-pointer team-index)
        plan (protocol/parse-driving-plan bytes length complete)
        kind (a/field plan kind)
        on-track (ak/== (a/field (a/deref racer) pit_state) pit-state-track)
        context (protocol/DrivingPlanContext
                  {:epoch race-epoch :tick simulation-tick
                   :latest_revision (a/field (a/deref state) revision)
                   :active (and on-track (a/field (a/deref state) enabled)
                                (ak/! (retired? index)) (ak/! (a/field (a/deref racer) finished))
                                (ak/! replay-active) (ak/== race-state race-state-running))
                   :left_clear (or (ak/!= kind protocol/plan-pass-left) (language-lane-clear? index 3.75))
                   :right_clear (or (ak/!= kind protocol/plan-pass-right) (language-lane-clear? index -3.75))
                   ;; Race-control flags are still a separate open task; do not
                   ;; fabricate flag observations in the language interface.
                   :red_flag false :overtaking_allowed on-track
                   :pit_available (and on-track (ak/== (a/field (a/deref team) pit_occupant) no-pit-occupant))})
        ^:var reason (protocol/validate-driving-plan plan epoch revision observed expires context)]
    (when (and (ak/== reason protocol/plan-ok) (ak/== kind protocol/plan-pit)
               (ak/! (request-driver-pit! team-index (ak/intCast index) true)))
      (ak/= reason protocol/plan-pit-unavailable))
    (when (ak/== reason protocol/plan-ok)
      (ak/= (a/deref state)
        (LanguageDriverState {:enabled true :kind kind :epoch epoch :revision revision :expires_tick expires}))
      (ak/= (a/field (a/deref brain) source)
        (if generated telemetry/source-llm telemetry/source-human))
      (ak/= (a/field (a/deref brain) lane_target)
        (cond (ak/== kind protocol/plan-pass-left) 0.075
              (ak/== kind protocol/plan-pass-right) -0.075
              :else (a/field (a/deref racer) lane)))
      (ak/= (a/field (a/deref brain) target_speed)
        (cond (ak/== kind protocol/plan-hold) 0.0
              (ak/== kind protocol/plan-yield) 0.025
              :else 0.080)))
    (ak/= language-event-sequence (+ language-event-sequence 1))
    (let [event (ak/& (a/index language-events (mod language-event-sequence 128)))
          stored (ak/min length 2048)]
      (ak/= (a/deref event)
        (LanguagePlanEvent {:valid true :accepted (ak/== reason protocol/plan-ok)
          :generated generated :racer (ak/intCast index) :reason reason :plan plan
          :sequence language-event-sequence :revision revision :epoch epoch
          :observed_tick observed :install_tick simulation-tick :expires_tick expires
          :byte_count (ak/intCast stored) :truncated (> length stored)
          :text (std-mem/zeroes (a/type [:array 2048 :u8]))}))
      (dotimes [i stored] (ak/= (a/index (a/field (a/deref event) text) i) (a/index bytes i))))
    reason))

(a/defn language-event-at LanguagePlanEvent
  "A bounded history lookup; valid=false means absent or already overwritten." [[sequence :u64]]
  (let [event (a/index language-events (mod sequence 128))]
    (if (and (> sequence 0) (ak/== (a/field event sequence) sequence)) event
      (std-mem/zeroes (a/type LanguagePlanEvent)))))

(a/defn language-exchange-count :u64 []
  (ak/atomicLoad :u64 (ak/& language-exchange-sequence) :.acquire))

(a/defn language-exchange-at LanguageExchange
  "Nonblocking, coherent copy for nREPL/UI. valid=false on a busy/absent slot;
  refresh to retry. No data race with the simulation's history writer." [[sequence :u64]]
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& language-exchange-lock) 0 1 :.acq_rel :.acquire) ak/null)
    (ak/return (std-mem/zeroes (a/type LanguageExchange))))
  (ak/defer (ak/atomicStore :u8 (ak/& language-exchange-lock) 0 :.release))
  (let [entry (a/index language-exchanges (mod sequence 128))]
    (if (and (> sequence 0) (a/field entry valid) (ak/== (a/field entry sequence) sequence)) entry
      (std-mem/zeroes (a/type LanguageExchange)))))

(a/defn request-language-mode! :bool
  "Thread-safe nREPL/UI command. The next native tick performs the change;
  the calling thread never reads or mutates Flecs/Box3D objects." [[index :usize] [enabled :bool]]
  (when (>= index racer-count) (ak/return false))
  (ak/atomicStore :u8 (ak/& (a/index language-mode-requests index))
                  (if enabled (ak/as 1 :u8) 2) :.release)
  true)

(a/defn step-language-driving! :void
  "Simulation-owned async handoff: no model execution or waiting here.
  Wake at 16 circuit markers, on a five-second incident recheck, or intent
  expiry. Existing valid intent controls the car while the worker thinks.
  This is driver scheduling; natural-language TEAM scheduling is still separate." []
  (dotimes [index racer-count]
    (let [mode (ak/atomicRmw :u8 (ak/& (a/index language-mode-requests index)) :.Xchg 0 :.acq_rel)
          schedule (ak/& (a/index language-schedules index))]
      (when (> mode 0)
        (ak/= :_ (enable-language-driving! index (ak/== mode 1)))
        ;; A mode toggle invalidates a reply that was already in flight.
        (ak/= (a/field (a/index language-drivers index) revision)
              (ak/max (a/field (a/index language-drivers index) revision)
                      (a/field (a/deref schedule) revision)))
        (ak/= (a/field (a/deref schedule) next_tick) simulation-tick)
        (ak/= (a/field (a/deref schedule) marker) 255))
      ;; If a reader is copying history, leave the worker result in its mailbox
      ;; until the next tick. Neither block physics nor drop an unread reply.
      (when (and (ak/== (worker/language-mailbox-state index) 3)
                 (ak/== (ak/cmpxchgStrong :u8 (ak/& language-exchange-lock) 0 1 :.acq_rel :.acquire) ak/null))
        (ak/defer (ak/atomicStore :u8 (ak/& language-exchange-lock) 0 :.release))
        (let [reply (worker/take-language-result! index)]
        (when (a/field reply valid)
          (let [request (a/field reply request) generation (a/field reply generation)
                reason (install-language-plan! index (a/field request epoch)
                         (a/field request revision) (a/field request observed_tick)
                         (a/field request expires_tick)
                         (ak/& (a/index (a/field generation bytes) 0)) (a/field generation byte_count)
                         (and (a/field generation valid) (ak/== (a/field generation stop) 1)) true)]
            (let [sequence (+ (language-exchange-count) 1)]
              (ak/= (a/index language-exchanges (mod sequence 128))
                (LanguageExchange {:valid true :sequence sequence :reason reason :result reply}))
              (ak/atomicStore :u64 (ak/& language-exchange-sequence) sequence :.release))))))
      (let [state (a/index language-drivers index) racer (a/deref (racer-pointer index))
            marker (ak/as (ak/intFromFloat (* 16.0 (track/wrap-progress (a/field racer progress)))) :u8)
            incident (or (< (a/field racer speed) 0.002) (> (ak/abs (a/field racer lane)) 0.13))]
        (when (and (a/field state enabled) (ak/! paused) (ak/! replay-active)
                   (ak/== race-state race-state-running) (ak/! (retired? index))
                   (ak/! (a/field racer finished))
                   (>= simulation-tick (a/field (a/deref schedule) next_tick))
                   (ak/== (worker/language-mailbox-state index) 0)
                   (or (ak/!= marker (a/field (a/deref schedule) marker)) incident
                       (>= simulation-tick (a/field state expires_tick))))
          (let [revision (+ (ak/max (a/field (a/deref schedule) revision) (a/field state revision)) 1)
                request (language-driving-request index revision)]
            ;; Back off invalid/busy submissions too, rather than rebuilding
            ;; physical clearance and text 120 times per second.
            (ak/= (a/field (a/deref schedule) next_tick) (+ simulation-tick 600))
            (when (worker/submit-language! request)
              (ak/= (a/field (a/deref schedule) revision) revision)
              (ak/= (a/field (a/deref schedule) marker) marker))))))))

(a/defn enforce-language-holds! :void
  "Final pedal gate AFTER automatic recovery. Hold/expiry brakes the physical
  car; it never changes velocity, pose, progress, or freezes the physics world." []
  (dotimes [i racer-count]
    (let [state (a/index language-drivers i)]
      (when (and (a/field state enabled)
                 (ak/== (a/field (a/deref (racer-pointer i)) pit_state) pit-state-track)
                 (or (ak/== (a/field state kind) protocol/plan-hold)
                     (ak/!= (a/field state epoch) race-epoch)
                     (>= simulation-tick (a/field state expires_tick))))
        (ak/= (a/field (a/index vehicle-controls i) throttle) 0.0)
        (ak/= (a/field (a/index vehicle-controls i) brake) 1.0)))))

(a/defn physical-contacts! :void
  "Damage comes from Box3D impacts, never from positional separation patches." []
  (let [events (b3/b3World_GetContactEvents dynamics-world)]
    (dotimes [event-index (ak/as (ak/intCast (a/field events hitCount)) :usize)]
      (let [hit (a/index (a/field events hitEvents) event-index)
            impact-speed (a/field hit approachSpeed)]
        (when (> impact-speed 3.0)
          (dotimes [side 2]
            (let [shape (if (ak/== side 0) (a/field hit shapeIdA) (a/field hit shapeIdB))
                  body (b3/b3Shape_GetBody shape)
                  tag (ak/intFromPtr (b3/b3Body_GetUserData body))]
              (when (and (> tag 0) (<= tag racer-count))
                (let [index (- tag 1) racer (racer-pointer index) brain (brain-pointer index)
                      other-shape (if (ak/== side 0) (a/field hit shapeIdB) (a/field hit shapeIdA))
                      other-tag (ak/intFromPtr (b3/b3Body_GetUserData (b3/b3Shape_GetBody other-shape)))
                      chassis (a/field (a/deref (vehicle-pointer index)) chassis)
                      rolling-contact (and (ak/== other-tag 0)
                                           (ak/!= (b3/b3StoreBodyId body) (b3/b3StoreBodyId chassis))
                                           (> (ak/abs (a/field (a/field hit normal) z)) 0.5))]
                  ;; A rotating polygonal tire can emit hit events against the
                  ;; road. Those are tire/road support, not chassis accidents.
                  (when (and (ak/! rolling-contact) (ak/! (retired? index))
                             (ak/== (a/index contact-cooldowns index) 0))
                    (ak/= (a/field (a/deref racer) damage)
                          (ak/min 1.0 (+ (a/field (a/deref racer) damage)
                                         (* (- impact-speed 3.0) 0.012))))
                    (ak/= (a/index contact-cooldowns index) 60)
                    (ak/= contact-count (+ contact-count 1))
                    (ak/= accident-count (+ accident-count 1))
                    (ak/= (a/field (a/deref brain) urgent) true)
                    (ak/= (a/field (a/deref brain) next_decision_tick) simulation-tick)
                    (radio-message! index radio-source-driver radio-car-damaged)))))))))))

(a/defn brake-for-traffic! :void
  "Measured occupied-lane braking before the solver. Does not choose overtakes
  for the AI, separate overlapping bodies, or override a human driver's input." []
  (dotimes [i racer-count]
    (when (ak/! (and human-controlled (ak/== i 0)))
      (let [racer (racer-pointer i)]
        (dotimes [j racer-count]
          (when (ak/!= i j)
            (let [front (racer-pointer j)
                  gap (* 4309.0 (mod (- (a/field (a/deref front) progress)
                                       (a/field (a/deref racer) progress)) 1.0))
                  lateral (* 50.0 (ak/abs (- (a/field (a/deref front) lane)
                                             (a/field (a/deref racer) lane))))]
              (when (and (< gap 500.0) (< lateral 2.7))
                (let [body (vehicle-pose j 0)
                      route (circuit/at-distance
                             (* 4309.0 (a/field (a/deref front) progress)) 0.0)
                      forward (ak/max 0.0
                                (+ (* (a/field body vx) (std-math/cos (a/field route heading)))
                                   (* (a/field body vy) (std-math/sin (a/field route heading)))))]
                  (ak/= (a/index vehicle-controls i)
                        (driver/yield-to-offset-obstacle
                          (a/index vehicle-controls i) gap forward
                          (* 50.0 (- (a/field (a/deref front) lane)
                                      (a/field (a/deref racer) lane))))))))))))))

(a/defn update-retirements! :void
  "Race-control classification from sustained physical evidence. Never moves,
  deletes or uprights a body, and never credits a retired car with a finish." []
  (when (ak/== race-state race-state-running)
    (dotimes [i racer-count]
      (let [entry (status-pointer i)
            previous (a/deref entry)
            racer (racer-pointer i)
            body (vehicle-pose i 0)
            up (- 1.0 (* 2.0 (+ (* (a/field body qx) (a/field body qx))
                               (* (a/field body qy) (a/field body qy)))))
            speed (ak/sqrt (+ (* (a/field body vx) (a/field body vx))
                             (* (a/field body vy) (a/field body vy))
                             (* (a/field body vz) (a/field body vz))))
            next (status/observe-world-position previous up speed
                                 (a/field body z) circuit/minimum-elevation simulation-tick
                                 (a/field (a/deref racer) lap)
                                 (a/field (a/deref racer) progress)
                                 (a/field (a/deref racer) finished))]
        (ak/= (a/deref entry) next)
        (when (and (a/field next retired) (ak/! (a/field previous retired)))
          (let [brain (brain-pointer i)
                team (team-pointer (a/field (a/deref racer) team))]
            (ak/= (a/field (a/deref brain) pending) false)
            (ak/= (a/field (a/deref brain) pending_revision) 0)
            (ak/= (a/field (a/deref brain) urgent) false)
            (ak/= (a/field (a/deref racer) boost_seconds) 0.0)
            (when (ak/== (a/field (a/deref team) pit_occupant) i)
              (ak/= (a/field (a/deref team) pit_occupant) no-pit-occupant))
            (radio-message! i radio-source-race-control
                            (if (ak/== (a/field next reason) status/reason-outside-world)
                              radio-outside-world radio-retired))))))))

(a/defn pit-navigation-active? :bool
  "A future pit call is not yet pit-lane driving. Share this boundary with
  manoeuvring so a called car can still clear an obstruction on the circuit." [[pit-state :u8] [progress :f32] [box-progress :f32]]
  (or (ak/== pit-state pit-state-servicing)
      (ak/== pit-state pit-state-exiting)
      (and (ak/== pit-state pit-state-called) (>= progress 0.85)
           (< progress (+ box-progress 0.01)))))

(a/defn update-recovery! :void
  "Observe front and rear physical traffic before a low-speed manoeuvre.
  The model's existing destination/steering remains authoritative." [[normal-controls [:array racer-count driver/Control]]]
  (ensure-recovery!)
  (let [^:var bodies (std-mem/zeroes (a/type [:array racer-count physics/BodyState]))]
    (dotimes [i racer-count]
      (ak/= (a/index bodies i) (vehicle-pose i 0)))
  (dotimes [i racer-count]
    (let [racer (racer-pointer i)
          brain (brain-pointer i)
          body (vehicle-pose i 0)
          normal (a/index normal-controls i)
          ^:var front-gap (ak/f32 1000.0)
          ^:var rear-gap (ak/f32 1000.0)
          ^:var rear-closing (ak/f32 0.0)
          route (circuit/at-distance (* 4309.0 (a/field (a/deref racer) progress)) 0.0)
          aligned (> (std-math/cos (- (a/field (a/deref racer) heading)
                                      (a/field route heading))) 0.8)
          enabled (and (ak/== race-state race-state-running)
                       (ak/! (retired? i)) (ak/! (a/field (a/deref racer) finished))
                       (ak/! (and human-controlled (ak/== i 0)))
                       (ak/! (pit-navigation-active?
                              (a/field (a/deref racer) pit_state)
                              (a/field (a/deref racer) progress)
                              (pit-box-progress (a/field (a/deref racer) team))))
                       aligned (< (ak/abs (a/field normal lane)) recovery/corridor-half-width))]
      (dotimes [j racer-count]
        (when (ak/!= i j)
          (let [other (racer-pointer j)
                ahead (* 4309.0 (mod (- (a/field (a/deref other) progress)
                                        (a/field (a/deref racer) progress)) 1.0))
                behind (- 4309.0 ahead)
                side (* 50.0 (ak/abs (- (a/field (a/deref other) lane)
                                         (a/field (a/deref racer) lane))))]
            (when (and (< side (turnaround/recovery-side-clearance body (a/index bodies j)
                                  (a/field route heading)))
                       (< (a/field (a/deref other) speed) 0.002))
              (ak/= front-gap (ak/min front-gap ahead)))
            ;; Wide rear corridor includes cars close enough to move into our
            ;; path. Conservative max closing speed cannot hide a fast car
            ;; behind a nearer slow one. The check repeats while reversing.
            (when (and (< side 5.4) (< behind 500.0))
              (ak/= rear-gap (ak/min rear-gap behind))
              (ak/= rear-closing (ak/max rear-closing
                                   (* 1000.0 (a/field (a/deref other) speed))))))))
      (let [entry (recovery-pointer i)
            ^:var output (recovery/step (a/field (a/deref entry) state) normal
                     (a/index vehicle-controls i) body
                     (* 50.0 (a/field (a/deref brain) lane_target))
                     front-gap rear-gap rear-closing enabled)]
        (when (and (ak/!= (a/field (a/field output state) phase) 0)
                   (< (a/field normal speed) 3.0))
          (ak/= (a/field output control)
            (turnaround/guard-recovery-control (a/field output control) body
              bodies racer-count i (a/field output gear) dynamics-world
              recovery/corridor-half-width)))
        ;; Inspection must report the same pedals that physics will receive.
        (ak/= (a/deref entry) output)
        (ak/= (a/index vehicle-controls i) (a/field output control)))))))

(a/defn update-turnarounds! :void
  "Physical body-relative manoeuvring takes precedence only after a spin.
  In ordinary driving the traffic/recovery controls remain unchanged." [[normal-controls [:array racer-count driver/Control]]]
  (ensure-turnaround!)
  (let [^:var bodies (std-mem/zeroes (a/type [:array racer-count physics/BodyState]))]
    (dotimes [i racer-count]
      (ak/= (a/index bodies i) (vehicle-pose i 0)))
    (dotimes [i racer-count]
      (let [racer (racer-pointer i)
            entry (turnaround-pointer i)
            previous (a/field (a/deref entry) state)
            normal (a/index normal-controls i)
            enabled (and (ak/== race-state race-state-running)
                         (ak/! (retired? i)) (ak/! (a/field (a/deref racer) finished))
                         (ak/! (and human-controlled (ak/== i 0)))
                         ;; Physical support extends to +/-35m; the planner
                         ;; queries the actual containment walls before moving.
                         (< (ak/abs (a/field normal lane)) 30.0)
                         (ak/! (pit-navigation-active?
                                 (a/field (a/deref racer) pit_state)
                                 (a/field (a/deref racer) progress)
                                 (pit-box-progress (a/field (a/deref racer) team)))))
            output (turnaround/step previous normal (a/index bodies i)
                                   bodies racer-count i enabled dynamics-world)]
        (ak/= (a/deref entry) output)
        (when (or (a/field previous active) (a/field (a/field output state) active))
          (ak/= (a/index vehicle-controls i) (a/field output control))
          (ak/= (a/field (a/deref (recovery-pointer i)) gear)
                (a/field (a/field output state) gear)))))))

(a/defn update-lap-timing! :void
  "Observe progression, never control it. Pauses/countdown do not accrue time." []
  (ensure-lap-timing!)
  (dotimes [i racer-count]
    (let [entry (lap-timing-pointer i)
          racer (racer-pointer i)]
      (ak/= (a/deref entry)
            (lap-timing/observe (a/deref entry) simulation-tick
              (a/field (a/deref racer) lap)
              (ak/== race-state race-state-running)
              (or (retired? i) (a/field (a/deref racer) finished)))))))

(a/defn step-dynamics! :void
  "Apply controls and solve contacts; derive telemetry from the resulting bodies." []
  (let [normal-controls vehicle-controls]
    (brake-for-traffic!)
    (update-recovery! normal-controls)
    (update-turnarounds! normal-controls))
  (enforce-language-holds!)
  (dotimes [i racer-count]
    ;; A finish is a classification result, not evidence that the car is still
    ;; driveable. Disable final pedals after a rollover even during cooldown.
    (ak/= (a/index vehicle-controls i)
      (driver/stop-if-overturned (a/index vehicle-controls i) (vehicle-pose i 0)))
    (when (> (a/index contact-cooldowns i) 0)
      (ak/= (a/index contact-cooldowns i) (- (a/index contact-cooldowns i) 1))))
  (dotimes [_ (ak/divTrunc physics/step-rate 120)]
    (dotimes [i racer-count]
      (let [control (a/index vehicle-controls i)]
        (physics/drive-in-gear! (a/deref (vehicle-pointer i))
          (a/field control throttle) (a/field control brake) (a/field control steering)
          (a/field (a/deref (recovery-pointer i)) gear))))
    (physics/step! dynamics-world)
    (physical-contacts!))
  (dotimes [i racer-count]
    (let [racer (racer-pointer i)
          state (vehicle-pose i 0)
          projected (track/project (* (a/field state x) 0.001) (* (a/field state y) 0.001))
          progress (a/field projected progress)
          previous (a/field (a/deref racer) progress)
          checkpoint (checkpoint-for-progress progress)
          expected (mod (+ (a/index lap-checkpoints i) 1) 4)]
      ;; A reverse crossing or a crash/cut skipping a checkpoint cannot create
      ;; a completed lap. Ranking still reflects the measured body position.
      (when (and (ak/== checkpoint expected)
                 (or (and (> progress previous) (< (- progress previous) 0.05))
                     (and (> previous 0.95) (< progress 0.05))))
        (ak/= (a/index lap-checkpoints i) checkpoint)
        (when (and (ak/== checkpoint 0) (ak/! (retired? i))
                   (ak/! (a/field (a/deref racer) finished)))
          (complete-lap! racer)))
      (ak/= (a/field (a/deref racer) progress) progress)
      (ak/= (a/field (a/deref racer) lane) (a/field projected lane))
      (ak/= (a/field (a/deref racer) x) (* (a/field state x) 0.001))
      (ak/= (a/field (a/deref racer) y) (* (a/field state y) 0.001))
      (ak/= (a/field (a/deref racer) speed)
            (* 0.001 (ak/sqrt (+ (* (a/field state vx) (a/field state vx))
                                 (* (a/field state vy) (a/field state vy))))))
      (ak/= (a/field (a/deref racer) heading)
            (std-math/atan2 (* 2.0 (+ (* (a/field state qw) (a/field state qz))
                                      (* (a/field state qx) (a/field state qy))))
                            (- 1.0 (* 2.0 (+ (* (a/field state qy) (a/field state qy))
                                              (* (a/field state qz) (a/field state qz))))))))))

(a/defn step-pit! :bool
  "Pit strategy controls pedals; service starts only after a physical stop." [[index :usize]]
  (let [racer (racer-pointer index) brain (brain-pointer index)
        team (team-pointer (ak/intCast (a/field (a/deref racer) team)))
        car (a/deref (vehicle-pointer index))
        progress (a/field (a/deref racer) progress)
        box-progress (pit-box-progress (a/field (a/deref racer) team))
        state (a/field (a/deref racer) pit_state)
        ^:var owns-control (ak/bool false)]
    (cond
      (and (ak/== state pit-state-called)
           (pit-navigation-active? state progress box-progress))
      (let [remaining (* (- box-progress progress) 4309.0)
            requested (ak/sqrt (* 6.0 (ak/max 0.0 (- remaining 0.5))))
            control (driver/follow-pit car requested 6.0)]
        (ak/= owns-control true)
        (ak/= (a/index vehicle-controls index) control)
        (when (and (< (ak/abs remaining) 1.0) (< (a/field control speed) 0.02))
          (ak/= (a/field (a/deref racer) pit_state) pit-state-servicing)
          (ak/= (a/field (a/deref racer) pit_seconds)
                (+ pit-service-seconds (* (a/field (a/deref racer) damage) pit-repair-extra-seconds)))
          (radio-message! index radio-source-driver radio-boxing-now)))

      (ak/== state pit-state-servicing)
      (do
        (ak/= owns-control true)
        (let [^:var control (driver/follow-pit car 0.0 6.0)]
          (ak/= (a/field control throttle) 0.0)
          (ak/= (a/field control brake) 1.0)
          (ak/= (a/index vehicle-controls index) control)
          ;; A collision moving the car pauses service; there is no position or
          ;; velocity lock that could turn it into an immovable obstacle.
          (when (< (a/field control speed) 0.02)
            (ak/= (a/field (a/deref racer) pit_seconds)
                  (ak/max 0.0 (- (a/field (a/deref racer) pit_seconds) fixed-step)))))
        (when (<= (a/field (a/deref racer) pit_seconds) 0.0)
          (let [repaired (> (a/field (a/deref racer) damage) 0.01)]
            (ak/= (a/field (a/deref racer) tire_condition) 1.0)
            (ak/= (a/field (a/deref racer) damage) 0.0)
            (ak/= (a/field (a/deref racer) tire_stage) 0)
            (ak/= (a/field (a/deref racer) damage_stage) 0)
            (ak/= (a/field (a/deref racer) pit_waiting) false)
            (ak/= (a/field (a/deref racer) pit_state) pit-state-exiting)
            (ak/= (a/field (a/deref racer) pit_stops) (+ (a/field (a/deref racer) pit_stops) 1))
            (ak/= (a/field (a/deref team) pit_stops) (+ (a/field (a/deref team) pit_stops) 1))
            (ak/= (a/field (a/deref team) pit_occupant) no-pit-occupant)
            (ak/= (a/field (a/deref team) instruction) team-action-hold)
            (ak/= pit-stop-count (+ pit-stop-count 1))
            (radio-message! index radio-source-strategist (if repaired radio-car-repaired radio-fresh-tires))
            (ak/= (a/field (a/deref brain) lane_target) 0.0)
            (ak/= (a/field (a/deref brain) target_speed) 0.070)
            (ak/= (a/field (a/deref brain) urgent) true)
            (ak/= (a/field (a/deref brain) next_decision_tick) simulation-tick))))

      (ak/== state pit-state-exiting)
      (do
        (ak/= owns-control true)
        (ak/= (a/index vehicle-controls index) (driver/follow-pit car 22.0 6.0))
        (when (and (< progress 0.5) (>= progress 0.055))
          (ak/= (a/field (a/deref racer) pit_state) pit-state-track)))
      :else (ak/= owns-control false))
    owns-control))

(a/defn step-racer! :void
  [[index :usize]]
  (let [racer (racer-pointer index)
        brain (brain-pointer index)
        reference-driver (and human-controlled (ak/== index 0))
        language-driver (a/field (a/index language-drivers index) enabled)]
    (when (retired? index)
      (ak/= (a/index vehicle-controls index)
            (driver/Control {:throttle 0.0 :brake 1.0 :steering 0.0
                             :progress (a/field (a/deref racer) progress)
                             :lane (* 50.0 (a/field (a/deref racer) lane))
                             :speed (* 1000.0 (a/field (a/deref racer) speed))}))
      (ak/return))
    (when reference-driver
      (apply-human-control!))
    (when (and (ak/! replay-active) (ak/! reference-driver) (ak/! language-driver))
      (install-worker-result! index))
    (when (and (ak/! replay-active) (ak/! reference-driver) (ak/! language-driver))
      (ak/= :_ (expire-pending-decision! index)))
    (when (and (ak/! replay-active)
               (ak/! reference-driver)
               (ak/! language-driver)
               (ak/! (a/field (a/deref racer) finished))
               (ak/! (a/field (a/deref brain) pending))
               (>= simulation-tick
                   (a/field (a/deref brain) next_decision_tick)))
      (let [urgent (a/field (a/deref brain) urgent)]
        (when (ak/! (submit-worker-request! index urgent))
          (make-decision! index urgent deadline-on-time))))
    (when (ak/== race-state race-state-running)
      (apply-item! index))
    (let [car (a/deref (vehicle-pointer index))
          running (ak/== race-state race-state-running)
          finished (a/field (a/deref racer) finished)
          boosted (> (a/field (a/deref racer) boost_seconds) 0.0)
          grip (+ 0.72 (* 0.28 (a/field (a/deref racer) tire_condition)))
          power-condition (- 1.0 (* 0.25 (a/field (a/deref racer) damage)))
          ;; Keep finishers moving on a controlled cooldown while others are
          ;; racing. Stopping on the line/pit merge can trap the final runner.
          ;; Classification is already fixed by finish_tick, not this motion.
          requested (if running
                      (if finished (ak/as 30.0 :f32)
                        (* 1000.0 grip power-condition
                           (+ (a/field (a/deref brain) target_speed) (if boosted (ak/as 0.035 :f32) 0.0))))
                      0.0)
          ^:var control (driver/follow-lane-plan car requested
                          (* (a/field (a/deref brain) lane_target) 50.0)
                          (ak/& (a/index lane-plans index)))]
      (when (ak/! running)
        (ak/= (a/field control throttle) 0.0)
        (ak/= (a/field control brake) 1.0))
      (when (and reference-driver running)
        (ak/= (a/field control throttle) human-throttle)
        (ak/= (a/field control brake) human-brake)
        (ak/= (a/field control steering) (* human-steering 0.45)))
      (ak/= (a/index vehicle-controls index) control))
    ;; A car may cross the finish while still inside the parallel pit lane.
    ;; Complete the actual paved exit before joining its cooldown lap.
    (when (and (ak/== race-state race-state-running)
               (a/field (a/deref racer) finished)
               (ak/== (a/field (a/deref racer) pit_state) pit-state-exiting))
      (ak/= :_ (step-pit! index)))
    (when (and (ak/== race-state race-state-running)
               (ak/! (a/field (a/deref racer) finished)))
      (update-tire-strategy! index)
      (ak/= :_ (step-pit! index))
      (ak/= (a/field (a/deref racer) stun_seconds)
            (ak/max 0.0 (- (a/field (a/deref racer) stun_seconds) fixed-step)))
      (ak/= (a/field (a/deref racer) boost_seconds)
            (ak/max 0.0 (- (a/field (a/deref racer) boost_seconds) fixed-step)))
      (ak/= (a/field (a/deref racer) pickup_cooldown)
            (ak/max 0.0 (- (a/field (a/deref racer) pickup_cooldown) fixed-step)))
      (collect-item! index))))

(a/defn resolve-racer-contacts! :void
  "Contact resolution is exclusively performed by Box3D inside step-dynamics!." []
  (physical-contacts!))

(a/defn classification-progress :f32 [[index :usize]]
  (if (retired? index)
    (let [entry (a/deref (status-pointer index))]
      (+ (ak/as (ak/floatFromInt (a/field entry lap)) :f32)
         (a/field entry progress)))
    (absolute-progress (racer-pointer index))))

(a/defn update-ranks! :void
  []
  (dotimes [index racer-count]
    (let [racer (racer-pointer index)
          ^:var rank (ak/u8 1)]
      (dotimes [other-index racer-count]
        (when (ak/!= index other-index)
          (let [other (racer-pointer other-index)
                other-ahead
                (cond
                  (and (a/field (a/deref other) finished)
                       (a/field (a/deref racer) finished))
                  (or (< (a/field (a/deref other) finish_tick)
                         (a/field (a/deref racer) finish_tick))
                      (and (ak/== (a/field (a/deref other) finish_tick)
                                  (a/field (a/deref racer) finish_tick))
                           (< (a/field (a/deref other) id)
                              (a/field (a/deref racer) id))))

                  (a/field (a/deref other) finished) true
                  (a/field (a/deref racer) finished) false

                  (and (retired? other-index) (ak/! (retired? index))) false
                  (and (retired? index) (ak/! (retired? other-index))) true

                  :else
                  (let [a (classification-progress other-index)
                        b (classification-progress index)]
                    (or (> a b) (and (ak/== a b) (< other-index index)))))]
            (when other-ahead
              (ak/= rank (+ rank 1))))))
      (ak/= (a/field (a/deref racer) rank) rank))))

(a/defn initialize! :bool
  "Create the Flecs world and exactly twenty independently scheduled AI racers."
  []
  (when (ak/! initialized)
    (ak/= world (flecs/ecs_init))
    (let [flecs-world (a/unwrap world)]
      (ak/= racer-component
            (register-component flecs-world "RacingRacer"
                                (ak/sizeOf Racer) (ak/alignOf Racer)))
      (ak/= brain-component
            (register-component flecs-world "RacingBrain"
                                (ak/sizeOf RacerBrain) (ak/alignOf RacerBrain)))
      (ak/= hazard-component
            (register-component flecs-world "RacingHazard"
                                (ak/sizeOf Hazard) (ak/alignOf Hazard)))
      (ak/= team-component
            (register-component flecs-world "RacingTeam"
                                (ak/sizeOf Team) (ak/alignOf Team)))
      (flecs/ecs_add_id flecs-world racer-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world brain-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world hazard-component flecs/EcsSparse)
      (flecs/ecs_add_id flecs-world team-component flecs/EcsSparse)
      (dotimes [index team-count]
        (let [entity (flecs/ecs_new flecs-world)
              identifier (ak/as (ak/intCast index) :u8)
              first-driver (ak/as (ak/intCast (* index protocol/drivers-per-team)) :u8)
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
          (ak/= (a/index team-entities index) entity)
          (flecs/ecs_set_id flecs-world entity team-component
                            (ak/sizeOf Team) (ak/& team))))
      (dotimes [index racer-count]
        (let [entity (flecs/ecs_new flecs-world)
              identifier (ak/as (ak/intCast index) :u8)
              seed-slot
              (ak/as (ak/intCast
                      (mod race-seed
                           (ak/as (ak/intCast racer-count) :u64)))
                     :usize)
              grid-index (mod (+ index seed-slot) racer-count)
              ;; Staggered 2-column grid, eight metres between rows.
              progress (* (ak/as (ak/floatFromInt grid-index) :f32) (/ 4.0 4309.0))
              lane (ak/f32 (if (ak/== (mod grid-index 2) 0) -0.05 0.05))
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
                      :aggression (+ 0.28 (* 0.595 (/ (ak/as (ak/floatFromInt index) :f32)
                                                     (ak/as (ak/floatFromInt (- racer-count 1)) :f32))))
                      :patience (- 0.86 (* 0.49 (/ (ak/as (ak/floatFromInt index) :f32)
                                                    (ak/as (ak/floatFromInt (- racer-count 1)) :f32))))
                      :risk (+ 0.20 (* 0.075 (ak/as (ak/floatFromInt (mod (+ index 3) 8)) :f32)))
                      :next_decision_tick (ak/as (ak/intCast (* index 5)) :u64)
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
          (ak/= (a/index entities index) entity)
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
              hazard (std-mem/zeroes (a/type Hazard))]
          (ak/= (a/index hazard-entities slot) entity)
          (flecs/ecs_set_id flecs-world entity hazard-component
                            (ak/sizeOf Hazard) (ak/& hazard)))))
    (ak/= initialized true))
  (ensure-dynamics!)
  (ensure-race-status!)
  (ensure-recovery!)
  (ensure-lap-timing!)
  initialized)

(a/defn coast-finished! :void
  "Use the brakes after finishing; the car remains a movable physical body." [[index :usize]]
  (let [^:var control (driver/follow (a/deref (vehicle-pointer index)) 0.0 0.0)]
    (ak/= (a/field control throttle) 0.0)
    (ak/= (a/field control brake) 1.0)
    (ak/= (a/index vehicle-controls index) control)))

(a/defn step! :void
  "Advance one deterministic 120 Hz simulation tick."
  []
  (ak/= :_ (initialize!))
  (update-thought-cadence!)
  (step-language-driving!)
  (when (ak/! paused)
    (when (and replay-active (ak/!= race-state race-state-finished))
      (install-replay-intents!))
    (dotimes [index racer-count]
      (step-racer! index))
    (step-dynamics!)
    (update-retirements!)
    (update-lap-timing!))
  (when (and (ak/! paused) (ak/!= race-state race-state-finished))
    (when (> countdown-ticks 0)
      (ak/= countdown-ticks (- countdown-ticks 1))
      (when (ak/== countdown-ticks 0)
        (ak/= race-state race-state-running)))
    (when (ak/== race-state race-state-running)
      (step-hazards!)
      (dotimes [team-index team-count]
        (step-team-strategist! team-index)))
    (update-ranks!)
    (let [^:var terminal-count (ak/u8 0)]
      (dotimes [index racer-count]
        (let [racer (racer-pointer index)]
          (when (or (a/field (a/deref racer) finished) (retired? index))
            (ak/= terminal-count (+ terminal-count 1)))
          (telemetry/resolve-due-outcomes!
           (a/field (a/deref racer) id)
           simulation-tick
           (a/field (a/deref racer) rank)
           (a/field (a/deref racer) lap)
           (a/field (a/deref racer) progress)
           (a/field (a/deref racer) finished))))
      (when (ak/== terminal-count racer-count)
        (ak/= race-state race-state-finished)))
    (ak/= :_ (flecs/ecs_progress world fixed-step))
    (ak/= human-use-item false)
    (ak/= simulation-tick (+ simulation-tick 1))))

(a/defn step-many! :void
  "Headless deterministic stepping used by nREPL and standalone tests."
  [[ticks :u32]]
  (dotimes [_ ticks]
    (step!)))

(a/defn racer-view RacerView
  "Inspect one AI using its stable zero-based racer id."
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
    (let [index (ak/as (ak/intCast identifier) :usize)
          racer (racer-pointer index)
          brain (brain-pointer index)
          team (team-pointer
                (ak/as (ak/intCast (a/field (a/deref racer) team))
                       :usize))]
      (RacerView
       {:valid true
        :id identifier
        :rank (a/field (a/deref racer) rank)
        :lap (a/field (a/deref racer) lap)
        :checkpoint
        (checkpoint-for-progress (a/field (a/deref racer) progress))
        :finished (a/field (a/deref racer) finished)
        :item (a/field (a/deref racer) item)
        :shielded (a/field (a/deref racer) shielded)
        :team (a/field (a/deref racer) team)
        :teammate (teammate-id identifier)
        :pit_state (a/field (a/deref racer) pit_state)
        :pit_stops (a/field (a/deref racer) pit_stops)
        :damage_stage (a/field (a/deref racer) damage_stage)
        :radio_code (a/field (a/deref racer) radio_code)
        :radio_source (a/field (a/deref racer) radio_source)
        :team_instruction (a/field (a/deref team) instruction)
        :team_pending (a/field (a/deref team) pending)
        :source (a/field (a/deref brain) source)
        :pending (a/field (a/deref brain) pending)
        :pending_urgent (a/field (a/deref brain) pending_urgent)
        :item_action (a/field (a/deref brain) item_action)
        :tire_condition (a/field (a/deref racer) tire_condition)
        :damage (a/field (a/deref racer) damage)
        :pit_seconds (a/field (a/deref racer) pit_seconds)
        :progress (a/field (a/deref racer) progress)
        :lane (a/field (a/deref racer) lane)
        :speed (a/field (a/deref racer) speed)
        :x (a/field (a/deref racer) x)
        :y (a/field (a/deref racer) y)
        :heading (a/field (a/deref racer) heading)
        :lane_target (a/field (a/deref brain) lane_target)
        :target_speed (a/field (a/deref brain) target_speed)
        :target (a/field (a/deref brain) target)
        :pending_age_ticks
        (if (and (a/field (a/deref brain) pending)
                 (>= simulation-tick
                     (a/field (a/deref brain) pending_tick)))
          (- simulation-tick (a/field (a/deref brain) pending_tick))
          0)
        :intent_age_ticks
        (if (>= simulation-tick
                (a/field (a/deref brain) last_decision_tick))
          (- simulation-tick (a/field (a/deref brain) last_decision_tick))
          0)
        :decision_revision (a/field (a/deref brain) decision_revision)
        :decisions (a/field (a/deref brain) decisions)
        :deadline_misses (a/field (a/deref brain) deadline_misses)
        :last_latency_us (a/field (a/deref brain) last_latency_us)
        :average_latency_us (a/field (a/deref brain) average_latency_us)
        :radio_revision (a/field (a/deref racer) radio_revision)
        :team_decision_revision (a/field (a/deref team) decision_revision)
        :team_decisions (a/field (a/deref team) decisions)
        :team_last_latency_us (a/field (a/deref team) last_latency_us)
        :team_average_latency_us (a/field (a/deref team) average_latency_us)
        :finish_tick (a/field (a/deref racer) finish_tick)}))))

(a/defn hazard-view HazardView
  "Inspect one stable pooled combat-object slot."
  [[slot :usize]]
  (if (or (ak/! initialized) (>= slot hazard-capacity))
    (HazardView {:valid false :active false :kind 0 :owner 0 :target 0
                 :progress 0.0 :lane 0.0 :decision_revision 0
                 :x 0.0 :y 0.0})
    (let [hazard (hazard-pointer slot)
          sample (track/pose (a/field (a/deref hazard) progress)
                             (a/field (a/deref hazard) lane))]
      (HazardView
       {:valid true
        :active (a/field (a/deref hazard) active)
        :kind (a/field (a/deref hazard) kind)
        :owner (a/field (a/deref hazard) owner)
        :target (a/field (a/deref hazard) target)
        :progress (a/field (a/deref hazard) progress)
        :lane (a/field (a/deref hazard) lane)
        :decision_revision (a/field (a/deref hazard) decision_revision)
        :x (a/field sample x)
        :y (a/field sample y)}))))

(a/defn snapshot RaceSnapshot
  "Return real race ordering and aggregate decision/combat telemetry."
  []
  (ak/= :_ (initialize!))
  (let [^:var finished (ak/u8 0)
        ^:var leader (ak/u8 0)
        ^:var leader-lap (ak/u16 0)
        ^:var leader-progress (ak/f32 0.0)
        ^:var decisions (ak/u64 0)
        ^:var urgent-decisions (ak/u64 0)
        ^:var invalid-decisions (ak/u64 0)
        ^:var deadline-misses (ak/u64 0)
        ^:var max-intent-age-ticks (ak/u64 0)
        ^:var active-hazards (ak/u8 0)]
    (dotimes [index racer-count]
      (let [racer (racer-pointer index)
            brain (brain-pointer index)]
        (when (a/field (a/deref racer) finished)
          (ak/= finished (+ finished 1)))
        (when (ak/== (a/field (a/deref racer) rank) 1)
          (ak/= leader (a/field (a/deref racer) id))
          (ak/= leader-lap (a/field (a/deref racer) lap))
          (ak/= leader-progress (a/field (a/deref racer) progress)))
        (ak/= decisions (+ decisions (a/field (a/deref brain) decisions)))
        (ak/= urgent-decisions
              (+ urgent-decisions (a/field (a/deref brain) urgent_decisions)))
        (ak/= invalid-decisions
              (+ invalid-decisions (a/field (a/deref brain) invalid_decisions)))
        (ak/= deadline-misses
              (+ deadline-misses (a/field (a/deref brain) deadline_misses)))
        (when (>= simulation-tick
                  (a/field (a/deref brain) last_decision_tick))
          (ak/= max-intent-age-ticks
                (ak/max max-intent-age-ticks
                        (- simulation-tick
                           (a/field (a/deref brain) last_decision_tick)))))))
    (dotimes [slot hazard-capacity]
      (when (a/field (a/deref (hazard-pointer slot)) active)
        (ak/= active-hazards (+ active-hazards 1))))
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
      :world_address (if (ak/== world ak/null)
                       0
                       (ak/intFromPtr (a/unwrap world)))})))

(a/defn mix-state-word :u64
  [[fingerprint :u64]
   [word :u64]]
  (ak/*% (ak/bit-xor fingerprint word) 1099511628211))

(a/defn mix-state-f32 :u64
  [[fingerprint :u64]
   [value :f32]]
  (let [bits (ak/u32 (ak/bitCast value))]
    (mix-state-word fingerprint (ak/as bits :u64))))

(a/defn state-fingerprint :u64
  "Hash canonical gameplay state field-by-field without struct padding,
  addresses, worker timings, or replay-control bookkeeping."
  []
  (ak/= :_ (initialize!))
  (let [^:var fingerprint (ak/u64 14695981039346656037)]
    (ak/= fingerprint (mix-state-word fingerprint simulation-tick))
    (ak/= fingerprint (mix-state-word fingerprint race-seed))
    (ak/= fingerprint
          (mix-state-word fingerprint (ak/as race-state :u64)))
    (ak/= fingerprint
          (mix-state-word fingerprint (ak/as countdown-ticks :u64)))
    (ak/= fingerprint
          (mix-state-word fingerprint (if human-controlled 1 0)))
    (ak/= fingerprint (mix-state-f32 fingerprint human-steering))
    (ak/= fingerprint (mix-state-f32 fingerprint human-throttle))
    (ak/= fingerprint (mix-state-f32 fingerprint human-brake))
    (ak/= fingerprint
          (mix-state-word fingerprint (if human-use-item 1 0)))
    (ak/= fingerprint (mix-state-word fingerprint item-use-count))
    (ak/= fingerprint (mix-state-word fingerprint hit-count))
    (ak/= fingerprint (mix-state-word fingerprint contact-count))
    (ak/= fingerprint (mix-state-word fingerprint hazard-spawn-count))
    (ak/= fingerprint (mix-state-word fingerprint pit-stop-count))
    (ak/= fingerprint (mix-state-word fingerprint team-radio-count))
    (dotimes [index racer-count]
      (let [racer (racer-pointer index)
            brain (brain-pointer index)
            classification (retirement-view index)]
        (ak/= fingerprint (mix-state-word fingerprint (if (a/field classification retired) 1 0)))
        (ak/= fingerprint (mix-state-word fingerprint (a/field classification reason)))
        (ak/= fingerprint (mix-state-word fingerprint (a/field classification invalid_ticks)))
        (ak/= fingerprint (mix-state-word fingerprint (a/field classification retired_tick)))
        (ak/= fingerprint (mix-state-word fingerprint (a/field classification lap)))
        (ak/= fingerprint (mix-state-f32 fingerprint (a/field classification progress)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) id)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) rank)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) lap)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (if (a/field (a/deref racer) finished) 1 0)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) item)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (if (a/field (a/deref racer) shielded) 1 0)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) team)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) pit_state)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) pit_stops)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref racer) radio_code)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) tire_condition)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) pit_seconds)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref racer) radio_revision)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) progress)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref racer) lane)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref racer) speed)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref racer) x)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref racer) y)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref racer) heading)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) stun_seconds)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) boost_seconds)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref racer) pickup_cooldown)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref racer) finish_tick)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref brain) racer_id)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref brain) pace)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref brain) item_action)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref brain) target)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (if (a/field (a/deref brain) urgent) 1 0)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (if (a/field (a/deref brain) pending) 1 0)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref brain) lane_target)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref brain) target_speed)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref brain) aggression)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref brain) patience)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref brain) risk)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref brain) next_decision_tick)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref brain) decision_revision)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref brain) decisions)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref brain) urgent_decisions)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref brain) invalid_decisions)))))
    (dotimes [index racer-count]
      (ak/= fingerprint
            (mix-state-word
             fingerprint (ak/as (a/index contact-cooldowns index) :u64)))
      (let [entry (recovery-view index) state (a/field entry state)]
        (ak/= fingerprint (mix-state-word fingerprint (ak/as (a/field state phase) :u64)))
        (ak/= fingerprint (mix-state-word fingerprint (ak/as (a/field state waiting_ticks) :u64)))
        (ak/= fingerprint (mix-state-f32 fingerprint (a/field state start_x)))
        (ak/= fingerprint (mix-state-f32 fingerprint (a/field state start_y)))
        (ak/= fingerprint (mix-state-word fingerprint
                            (ak/as (ak/intCast (+ (ak/as (a/field entry gear) :i32) 1)) :u64)))))
    (dotimes [index team-count]
      (let [team (team-pointer index)]
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref team) pit_occupant)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref team) pit_stops)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (a/field (a/deref team) radio_sequence)))))
    (dotimes [slot hazard-capacity]
      (let [hazard (hazard-pointer slot)]
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (if (a/field (a/deref hazard) active) 1 0)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref hazard) kind)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref hazard) owner)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-word fingerprint
                              (ak/as (a/field (a/deref hazard) target)
                                     :u64)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref hazard) progress)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref hazard) lane)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref hazard) speed)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint
                             (a/field (a/deref hazard) arming_seconds)))
        (ak/= fingerprint
              (mix-state-f32 fingerprint (a/field (a/deref hazard) ttl)))
        (ak/= fingerprint
              (mix-state-word
               fingerprint (a/field (a/deref hazard) decision_revision)))))
    fingerprint))

(a/defn toggle-paused! :bool
  []
  (ak/= paused (ak/! paused))
  paused)

(a/defn configure-countdown! :void
  "Set the deterministic start countdown in simulation ticks and apply it to
  the current race. Desktop uses 360 ticks (three seconds); headless fixtures
  can keep zero for maximum-speed deterministic evaluation."
  [[ticks :u16]]
  (ak/= configured-countdown-ticks ticks)
  (ak/= countdown-ticks ticks)
  (ak/= race-state
        (if (> ticks 0) race-state-countdown race-state-running)))

(a/defn set-items-enabled! :bool
  "Enable normal pickups or run a deterministic no-item race. The setting is
  explicit and survives reset so tests and nREPL experiments can own it."
  [[enabled :bool]]
  (do
    (ak/= items-enabled enabled)
    items-enabled))

(a/defn set-human-controlled! :bool
  "Enable or disable optional keyboard/gamepad control for racer 0. All twenty
  racers remain AI-controlled by default. Switching invalidates any old
  in-flight racer-0 result without restarting the world or workers."
  [[enabled :bool]]
  (do
    (ak/= human-controlled enabled)
    (when initialized
      (let [brain (brain-pointer 0)]
        (ak/= (a/field (a/deref brain) pending) false)
        (ak/= (a/field (a/deref brain) pending_urgent) false)
        (ak/= (a/field (a/deref brain) pending_target) 0)
        (ak/= (a/field (a/deref brain) pending_revision) 0)
        (ak/= (a/field (a/deref brain) pending_tick) 0)
        (ak/= (a/field (a/deref brain) urgent) false)
        (ak/= (a/field (a/deref brain) item_action) action-hold)
        (ak/= (a/field (a/deref brain) source)
              (if enabled telemetry/source-human telemetry/source-fallback))
        (ak/= (a/field (a/deref brain) decision_revision)
              (next-decision-revision!))
        (ak/= (a/field (a/deref brain) last_decision_tick) simulation-tick)
        (ak/= (a/field (a/deref brain) next_decision_tick) simulation-tick)))
    human-controlled))

(a/defn set-human-input! :void
  "Install normalized reference-driver input. Values are clamped again inside
  the fixed-step controller, so keyboard, gamepad, and nREPL use one safe path."
  [[steering :f32]
   [throttle :f32]
   [brake :f32]
   [use-item :bool]]
  (ak/= human-steering steering)
  (ak/= human-throttle throttle)
  (ak/= human-brake brake)
  (ak/= human-use-item use-item))

(a/defn human-control-snapshot HumanControlSnapshot
  "Inspect the exact reference-driver input currently consumed by native code."
  []
  (HumanControlSnapshot {:enabled human-controlled
                         :steering human-steering
                         :throttle human-throttle
                         :brake human-brake
                         :use_item human-use-item}))

(a/defn set-race-seed! :void
  "Choose the deterministic starting-grid and pickup permutation used by the
  next explicit `reset!`. The running world is never mutated implicitly."
  [[seed :u64]]
  (ak/= race-seed seed))

(a/defn configure-racer-state! :bool
  "Safely edit one live racer's physical/item state for REPL scenarios. The
  fixed-step controller remains authoritative after this explicit mutation."
  [[identifier :u8]
   [progress :f32]
   [lane :f32]
   [speed :f32]
   [item :u8]
   [shielded :bool]]
  (do
    (ak/= :_ (initialize!))
    (if (or (>= identifier racer-count) (> item item-surge))
      false
      (let [racer (racer-pointer identifier)]
        (ak/= (a/field (a/deref racer) progress)
              (track/wrap-progress progress))
        (ak/= (a/field (a/deref racer) lane)
              (ak/min 0.075 (ak/max -0.075 lane)))
        (ak/= (a/field (a/deref racer) speed)
              (ak/min 0.16 (ak/max 0.0 speed)))
        (ak/= (a/field (a/deref racer) item) item)
        (ak/= (a/field (a/deref racer) shielded) shielded)
        (ak/= (a/field (a/deref racer) stun_seconds) 0.0)
        (ak/= (a/field (a/deref racer) boost_seconds) 0.0)
        (ak/= (a/field (a/deref racer) pickup_cooldown) 1.0)
        (update-position! racer)
        (update-ranks!)
        true))))

(a/defn configure-racer-tires! :bool
  "Set bounded tire condition for a live REPL/test scenario. Normal racing
  immediately resumes authoritative wear, grip, radio, and pit strategy."
  [[identifier :u8]
   [condition :f32]]
  (do
    (ak/= :_ (initialize!))
    (if (>= identifier racer-count)
      false
      (let [racer (racer-pointer (ak/intCast identifier))]
        (ak/= (a/field (a/deref racer) tire_condition)
              (ak/min 1.0 (ak/max 0.0 condition)))
        (ak/= (a/field (a/deref racer) tire_stage) 0)
        (ak/= (a/field (a/deref racer) pit_waiting) false)
        true))))

(a/defn configure-racer-damage! :bool
  "Set bounded persistent car damage for a live REPL/test scenario. The team
  strategist observes it on the next fixed tick and can call the car to repair."
  [[identifier :u8]
   [damage :f32]]
  (do
    (ak/= :_ (initialize!))
    (if (>= identifier racer-count)
      false
      (let [racer (racer-pointer (ak/intCast identifier))]
        (ak/= (a/field (a/deref racer) damage)
              (ak/min 1.0 (ak/max 0.0 damage)))
        (ak/= (a/field (a/deref racer) damage_stage) 0)
        (ak/= (a/field (a/deref racer) pit_waiting) false)
        true))))

(a/defn configure-racer-intent! :bool
  "Install one bounded live intent for REPL scenarios. It uses the same
  RacerBrain fields as model output and remains active until the next thought."
  [[identifier :u8]
   [lane-target :f32]
   [target-speed :f32]
   [item-action :u8]
   [target :u8]]
  (do
    (ak/= :_ (initialize!))
    (if (or (>= identifier racer-count)
            (>= target racer-count)
            (> item-action action-use))
      false
      (let [brain (brain-pointer identifier)]
        (ak/= (a/field (a/deref brain) lane_target)
              (ak/min 0.075 (ak/max -0.075 lane-target)))
        (ak/= (a/field (a/deref brain) target_speed)
              (ak/min 0.16 (ak/max 0.0 target-speed)))
        (ak/= (a/field (a/deref brain) item_action) item-action)
        (ak/= (a/field (a/deref brain) target) target)
        (ak/= (a/field (a/deref brain) source) telemetry/source-fallback)
        (ak/= (a/field (a/deref brain) pending) false)
        (ak/= (a/field (a/deref brain) pending_urgent) false)
        (ak/= (a/field (a/deref brain) pending_target) target)
        (ak/= (a/field (a/deref brain) pending_revision) 0)
        (ak/= (a/field (a/deref brain) pending_tick) 0)
        (ak/= (a/field (a/deref brain) urgent) false)
        (ak/= (a/field (a/deref brain) next_decision_tick)
              (+ simulation-tick current-ordinary-thought-ticks))
        true))))

(a/defn reset! :void
  "Explicitly recreate the Flecs race. Ordinary hot reload never calls this."
  []
  (destroy-dynamics!)
  (when (ak/!= world ak/null)
    (ak/= :_ (flecs/ecs_fini world)))
  (ak/= world ak/null)
  (ak/= status-component 0)
  (ak/= recovery-component 0)
  (ak/= turnaround-component 0)
  (ak/= lap-timing-component 0)
  (ak/= initialized false)
  (ak/= paused false)
  (ak/= countdown-ticks configured-countdown-ticks)
  (ak/= race-state
        (if (> countdown-ticks 0)
          race-state-countdown
          race-state-running))
  (ak/= human-steering 0.0)
  (ak/= human-throttle 0.0)
  (ak/= human-brake 0.0)
  (ak/= human-use-item false)
  (ak/= replay-active false)
  (ak/= replay-cursor 0)
  (ak/= simulation-tick 0)
  (ak/= current-ordinary-thought-ticks ordinary-thought-ticks)
  (ak/= cadence-pending 0)
  (ak/= cadence-max-latency-us 0)
  (ak/= cadence-adaptations 0)
  (ak/= item-use-count 0)
  (ak/= hit-count 0)
  (ak/= contact-count 0)
  (ak/= contact-cooldowns
        (std-mem/zeroes (a/type [:array racer-count :u8])))
  (ak/= hazard-spawn-count 0)
  (ak/= pit-stop-count 0)
  (ak/= team-radio-count 0)
  (ak/= accident-count 0)
  (ak/= team-ai-decision-count 0)
  (ak/= team-radio-history
        (std-mem/zeroes (a/type [:array (* team-count team-radio-history-per-team) TeamRadioLog])))
  (ak/= team-radio-heads (std-mem/zeroes (a/type [:array team-count :u8])))
  (ak/= team-radio-counts (std-mem/zeroes (a/type [:array team-count :u8])))
  (ak/= race-epoch (+ race-epoch 1))
  (ak/= language-drivers (std-mem/zeroes (a/type [:array racer-count LanguageDriverState])))
  (ak/= language-events (std-mem/zeroes (a/type [:array 128 LanguagePlanEvent])))
  (ak/= language-event-sequence 0)
  (ak/= language-schedules (std-mem/zeroes (a/type [:array racer-count LanguageSchedule])))
  ;; Keep bounded immutable language exchanges across races: their request
  ;; epochs preserve provenance. Reset never races a UI/history reader.
  (ak/= entities (std-mem/zeroes (a/type [:array racer-count :u64])))
  (ak/= hazard-entities (std-mem/zeroes (a/type [:array 32 :u64])))
  (ak/= team-entities (std-mem/zeroes (a/type [:array team-count :u64])))
  (telemetry/reset!)
  (ak/= :_ (initialize!)))

(a/defn start-replay! :bool
  "Reset the race and install only the previously loaded intent stream."
  []
  (let [available (> replay-count 0)]
    (when available
      (reset!)
      (ak/= replay-cursor 0)
      (ak/= replay-active true))
    available))

(a/defn stop-replay! :void
  "Leave the current replayed world intact and resume normal cognition."
  []
  (ak/= replay-active false))

(a/defn run-replay-parity! ReplayParityReport
  "Run, capture, reset, and replay one deterministic native race segment."
  [[ticks :u32]]
  (clear-replay!)
  ;; The golden parity gate is seed-zero by definition and must not inherit the
  ;; last tournament/game seed or interactive control mode from a long-lived
  ;; development process.
  (ak/= race-seed 0)
  (ak/= configured-countdown-ticks 0)
  (ak/= countdown-ticks 0)
  (ak/= race-state race-state-running)
  (ak/= human-controlled false)
  (ak/= human-steering 0.0)
  (ak/= human-throttle 0.0)
  (ak/= human-brake 0.0)
  (ak/= human-use-item false)
  (ak/= decision-sequence 0)
  (reset!)
  (step-many! ticks)
  (let [original (state-fingerprint)
        captured (capture-retained-replay!)
        count (a/field captured loaded)
        started (start-replay!)]
    (when started
      (step-many! ticks))
    (let [replayed (state-fingerprint)]
      (ReplayParityReport
       {:valid (and started (> count 0) (ak/== original replayed))
        :intent_count count :ticks ticks
        :original_fingerprint original
        :replay_fingerprint replayed}))))

(a/defn shutdown! :void
  []
  (destroy-dynamics!)
  (when (ak/!= world ak/null)
    (ak/= :_ (flecs/ecs_fini world)))
  (ak/= world ak/null)
  (ak/= status-component 0)
  (ak/= recovery-component 0)
  (ak/= turnaround-component 0)
  (ak/= lap-timing-component 0)
  (ak/= replay-active false)
  (ak/= initialized false))
