(ns racing-game.monitor
  "Dear ImGui cognition monitor for development and the demonstrator release.

  The game, model workers, Flecs world, and Vulkan renderer remain the same
  native code; this module only projects bounded public telemetry into ImGui."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.desktop :as desktop]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]
            [racing-game.worker :as worker]
            [aguafria-examples-native.imgui-bindings]
            [aguafria-examples-native.bindings.imgui :as imgui]))

(az/defstruct MonitorRacer
  "Aguafria-owned mirror of the stable development-monitor C ABI."
  {:layout :extern}
  [[:valid :bool]
   [:detailed_observation :bool]
   [:urgent :bool]
   [:pending :bool]
   [:accepted :bool]
   [:outcome_resolved :bool]
   [:outcome_item_used :bool]
   [:id :u8]
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
   [:rank :u8]
   [:item :u8]
   [:target :u8]
   [:persona :u8]
   [:target_lane :u8]
   [:tactical_status :u8]
   [:lane_choice :u8]
   [:pace_choice :u8]
   [:item_choice :u8]
   [:deadline_status :u8]
   [:start_rank :u8]
   [:end_rank :u8]
   [:lap :u16]
   [:hits_dealt :u16]
   [:progress_bin :u8]
   [:speed_bin :u8]
   [:target_distance_bin :u8]
   [:model_step_count :u8]
   [:revision :u64]
   [:radio_revision :u64]
   [:team_decision_revision :u64]
   [:team_decisions :u64]
   [:team_last_latency_us :u64]
   [:team_average_latency_us :u64]
   [:decisions :u64]
   [:deadline_misses :u64]
   [:pending_age_ticks :u64]
   [:queue_us :u64]
   [:total_us :u64]
   [:progress :f32]
   [:speed :f32]
   [:steps_per_second :f32]
   [:progress_gain :f32]
   [:tire_condition :f32]
   [:damage :f32]
   [:pit_seconds :f32]
   [:prompt [:array 161 :u8]]
   [:response [:array 2 :u8]]
   [:input_tokens [:array 8 :u32]]
   [:input_token_count :u32]
   [:output_token :u32]])

(az/defstruct MonitorRadio
  "Stable semantic team-radio entry shared with the ImGui presentation layer."
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
   [:prompt [:array 161 :u8]]])

(az/defstruct MonitorSnapshot
  "Allocation-free state shared with the optional C++ presentation layer."
  {:layout :extern}
  [[:tick :u64]
   [:total_decisions :u64]
   [:llm_decisions :u64]
   [:fallback_decisions :u64]
   [:rejected_decisions :u64]
   [:deadline_misses :u64]
   [:resolved_outcomes :u64]
   [:worker_requests :u64]
   [:worker_results :u64]
   [:worker_state_bytes :u64]
   [:pending_requests :u32]
   [:average_steps_per_second :f32]
   [:racers [:array 8 MonitorRacer]]
   [:history_counts [:array 8 :u8]]
   [:history [:array 512 MonitorRacer]]
   [:radio_counts [:array 4 :u8]]
   [:radio [:array 128 MonitorRadio]]])

(az/defvar initialized false)

(az/defvar snapshot MonitorSnapshot
  (std-mem/zeroes (az/type MonitorSnapshot)))

(az/defvar history-raw-visible false)

(az/defn progress-bin
  {:export false :implicit-return true}
  :-
  :u8
  [[progress :f32]]
  (ak/as :u8
         (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 progress) 10.0)))))

(az/defn speed-bin
  {:export false :implicit-return true}
  :-
  :u8
  [[speed :f32]]
  (ak/as :u8
         (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 speed) 100.0)))))

(az/defn lane-choice
  {:export false :implicit-return true}
  :-
  :u8
  [[lane-target :f32]]
  (cond
    (< lane-target -0.025) 0
    (> lane-target 0.025) 2
    :else 1))

(az/defn pace-choice
  {:export false :implicit-return true}
  :-
  :u8
  [[target-speed :f32]]
  (cond
    (< target-speed 0.076) 0
    (< target-speed 0.084) 1
    :else 2))

(az/defn refresh-racer!
  "Copy one bounded semantic decision into the current row or history ABI."
  {:export false}
  :-
  :void
  [[identifier :u8]
   [offset :usize]
   [destination :usize]
   [history-row :bool]
   [include-raw :bool]]
  (let [index (ak/as :usize (ak/intCast identifier))
        view (simulation/racer-view identifier)
        entry (telemetry/entry-at identifier offset)
        outcome (telemetry/outcome-at identifier offset)
        detailed
        (and (az/field entry valid)
             (ak/== (az/field entry source) telemetry/source-llm)
             (> (az/field entry prompt_byte_count) 0))
        ^:var row
        (MonitorRacer
         {:valid (if history-row
                   (az/field entry valid)
                   (az/field view valid))
          :detailed_observation detailed
          :urgent (az/field entry urgent)
          :pending (if history-row false (az/field view pending))
          :accepted (az/field entry accepted)
          :outcome_resolved (az/field outcome resolved)
          :outcome_item_used (az/field outcome item_used)
          :id identifier
          :team (az/field view team)
          :teammate (az/field view teammate)
          :pit_state (az/field view pit_state)
          :pit_stops (az/field view pit_stops)
          :damage_stage (az/field view damage_stage)
          :radio_code (az/field view radio_code)
          :radio_source (az/field view radio_source)
          :team_instruction (az/field view team_instruction)
          :team_pending (az/field view team_pending)
          :source (az/field entry source)
          :rank (az/field entry rank)
          :item (az/field entry item)
          :target (az/field entry target)
          :persona 0
          :target_lane 1
          :tactical_status 0
          :lane_choice (lane-choice (az/field entry lane_target))
          :pace_choice (pace-choice (az/field entry target_speed))
          :item_choice (if (> (az/field entry action) 0) 1 0)
          :deadline_status (az/field entry deadline_status)
          :start_rank (az/field outcome start_rank)
          :end_rank (az/field outcome end_rank)
          :lap (az/field entry lap)
          :hits_dealt (az/field outcome hits_dealt)
          :progress_bin (progress-bin (az/field entry progress))
          :speed_bin (speed-bin (az/field entry speed))
          :target_distance_bin 0
          :model_step_count
          (ak/intCast (ak/min (az/field entry input_token_count) 255))
          :revision (az/field entry revision)
          :radio_revision (az/field view radio_revision)
          :team_decision_revision (az/field view team_decision_revision)
          :team_decisions (az/field view team_decisions)
          :team_last_latency_us (az/field view team_last_latency_us)
          :team_average_latency_us (az/field view team_average_latency_us)
          :decisions (az/field view decisions)
          :deadline_misses (az/field view deadline_misses)
          :pending_age_ticks (az/field view pending_age_ticks)
          :queue_us (az/field entry queue_us)
          :total_us (az/field entry total_us)
          :progress (az/field entry progress)
          :speed (az/field entry speed)
          :steps_per_second (az/field entry tokens_per_second)
          :progress_gain (az/field outcome progress_gain)
          :tire_condition (az/field view tire_condition)
          :damage (az/field view damage)
          :pit_seconds (az/field view pit_seconds)
          :prompt (std-mem/zeroes (az/type [:array 161 :u8]))
          :response (std-mem/zeroes (az/type [:array 2 :u8]))
          :input_tokens (std-mem/zeroes (az/type [:array 8 :u32]))
          :input_token_count 0
          :output_token 0})]
    ;; The human-readable observation is part of the normal monitor. Numeric
    ;; token IDs and constrained output remain behind the explicit raw toggle.
    (when detailed
      (let [prompt-count
            (ak/min worker/prompt-capacity
                    (ak/as :usize
                           (ak/intCast (az/field entry prompt_byte_count))))]
        (dotimes [position prompt-count]
          (set! (az/index (az/field row prompt) position)
                (az/index (az/field entry prompt_bytes) position)))))
    (when (and include-raw detailed)
      (let [input-count
            (ak/min (ak/as :usize (ak/intCast (az/field entry input_token_count)))
                    8)]
        (when (> (az/field entry response_byte_count) 0)
          (set! (az/index (az/field row response) 0)
                (az/index (az/field entry response_bytes) 0)))
        (dotimes [position input-count]
          (set! (az/index (az/field row input_tokens) position)
                (az/index (az/field entry input_tokens) position)))
        (set! (az/field row input_token_count)
              (az/field entry input_token_count))
        (when (> (az/field entry output_token_count) 0)
          (set! (az/field row output_token)
                (az/index (az/field entry output_tokens) 0)))))
    (if history-row
      (set! (az/index (az/field snapshot history) destination) row)
      (set! (az/index (az/field snapshot racers) index) row))))

(az/defn refresh-radio!
  "Copy one semantic newest-first team exchange into the stable C ABI."
  {:export false}
  :-
  :void
  [[team-id :u8]
   [offset :usize]
   [destination :usize]]
  (let [entry (simulation/team-radio-entry team-id offset)
        prompt-count
        (ak/min worker/prompt-capacity
                (ak/as :usize
                       (ak/intCast (az/field entry prompt_byte_count))))
        ^:var row
        (MonitorRadio
         {:valid (az/field entry valid)
          :team (az/field entry team)
          :source (az/field entry source)
          :target (az/field entry target)
          :code (az/field entry code)
          :pit_state (az/field entry pit_state)
          :instruction (az/field entry instruction)
          :reserved 0
          :model_accepted (az/field entry model_accepted)
          :model_action (az/field entry model_action)
          :prompt_byte_count (ak/intCast prompt-count)
          :input_token_count (az/field entry input_token_count)
          :best_token (az/field entry best_token)
          :tick (az/field entry tick)
          :decision_revision (az/field entry decision_revision)
          :latency_us (az/field entry latency_us)
          :tokens_per_second (az/field entry tokens_per_second)
          :tire_condition (az/field entry tire_condition)
          :damage (az/field entry damage)
          :prompt (std-mem/zeroes (az/type [:array 161 :u8]))})]
    (dotimes [position prompt-count]
      (set! (az/index (az/field row prompt) position)
            (az/index (az/field entry prompt_bytes) position)))
    (set! (az/index (az/field snapshot radio) destination) row)))

(az/defn refresh!
  "Refresh the allocation-free native snapshot consumed by Dear ImGui."
  {:attrs #{:public}}
  :-
  :void
  []
  (let [race (simulation/snapshot)
        cognition (telemetry/summary)
        workers (worker/summary)
        include-raw (imgui/aguafria_imgui_raw_protocol_visible)
        history-changed
        (or (ak/!= (az/field snapshot total_decisions)
                   (az/field cognition total_entries))
            (ak/!= (az/field snapshot resolved_outcomes)
                   (az/field cognition resolved_outcomes))
            (ak/!= history-raw-visible include-raw))]
    (set! (az/field snapshot tick) (az/field race tick))
    (set! (az/field snapshot total_decisions)
          (az/field cognition total_entries))
    (set! (az/field snapshot llm_decisions) (az/field cognition llm_entries))
    (set! (az/field snapshot fallback_decisions)
          (az/field cognition fallback_entries))
    (set! (az/field snapshot rejected_decisions)
          (az/field cognition rejected_entries))
    (set! (az/field snapshot deadline_misses)
          (az/field cognition deadline_misses))
    (set! (az/field snapshot resolved_outcomes)
          (az/field cognition resolved_outcomes))
    (set! (az/field snapshot worker_requests) (az/field workers requests))
    (set! (az/field snapshot worker_results) (az/field workers results))
    (set! (az/field snapshot worker_state_bytes)
          (ak/intCast (az/field workers state_bytes)))
    (set! (az/field snapshot pending_requests)
          (ak/intCast (az/field workers pending)))
    (set! (az/field snapshot average_steps_per_second)
          (az/field cognition average_tokens_per_second))
    (dotimes [identifier 8]
      (refresh-racer! (ak/intCast identifier) 0 identifier false include-raw))
    (dotimes [team-id 4]
      (let [radio-count
            (ak/as :usize
                   (ak/intCast
                    (simulation/team-radio-history-count
                     (ak/intCast team-id))))]
        (set! (az/index (az/field snapshot radio_counts) team-id)
              (ak/intCast radio-count))
        (dotimes [offset 32]
          (let [destination (+ (* team-id 32) offset)]
            (if (< offset radio-count)
              (refresh-radio! (ak/intCast team-id) offset destination)
              (set! (az/index (az/field snapshot radio) destination)
                    (std-mem/zeroes (az/type MonitorRadio))))))))
    (when history-changed
      (dotimes [identifier 8]
        (let [history-count
              (ak/as :usize
                     (ak/intCast
                      (ak/min (telemetry/decision-count (ak/intCast identifier))
                              telemetry/entries-per-racer)))]
          (set! (az/index (az/field snapshot history_counts) identifier)
                (ak/intCast history-count))
          (dotimes [offset 64]
            (let [destination (+ (* identifier 64) offset)]
              (if (< offset history-count)
                (refresh-racer! (ak/intCast identifier) offset destination
                                true include-raw)
                (set! (az/index (az/field snapshot history) destination)
                      (std-mem/zeroes (az/type MonitorRacer))))))))
      (set! history-raw-visible include-raw))
    (imgui/aguafria_imgui_update (ak/ptrCast (ak/& snapshot)))))

(az/defn monitor-snapshot
  "Inspectable native snapshot used by the ImGui layer."
  {:attrs #{:public :implicit-return}}
  :-
  MonitorSnapshot
  []
  snapshot)

(az/defn monitor-racer
  "Inspect one decoded monitor row without exposing nested ABI bytes."
  {:attrs #{:public :implicit-return}}
  :-
  MonitorRacer
  [[identifier :u8]]
  (if (< identifier 8)
    (az/index (az/field snapshot racers) (ak/intCast identifier))
    (std-mem/zeroes (az/type MonitorRacer))))

(az/defn monitor-history-count
  "Number of retained native decisions exposed for one racer."
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[identifier :u8]]
  (if (< identifier 8)
    (az/index (az/field snapshot history_counts) (ak/intCast identifier))
    0))

(az/defn monitor-history-entry
  "Inspect one newest-first decision from a racer's native telemetry ring."
  {:attrs #{:public :implicit-return}}
  :-
  MonitorRacer
  [[identifier :u8]
   [offset :u8]]
  (if (and (< identifier 8)
           (< offset (monitor-history-count identifier)))
    (az/index (az/field snapshot history)
              (+ (* (ak/as :usize (ak/intCast identifier)) 64)
                 (ak/as :usize (ak/intCast offset))))
    (std-mem/zeroes (az/type MonitorRacer))))

(az/defn monitor-radio-count
  "Number of semantic exchanges visible for one team in the current UI state."
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[team-id :u8]]
  (if (< team-id 4)
    (az/index (az/field snapshot radio_counts) (ak/intCast team-id))
    0))

(az/defn monitor-radio-entry
  "Inspect one newest-first team/driver exchange exactly as shown in ImGui."
  {:attrs #{:public :implicit-return}}
  :-
  MonitorRadio
  [[team-id :u8]
   [offset :u8]]
  (if (and (< team-id 4) (< offset (monitor-radio-count team-id)))
    (az/index (az/field snapshot radio)
              (+ (* (ak/as :usize (ak/intCast team-id)) 32)
                 (ak/as :usize (ak/intCast offset))))
    (std-mem/zeroes (az/type MonitorRadio))))

(az/defn abi-valid?
  "Verify the generated Zig structs exactly match their C++ ABI."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (and
   (ak/== (ak/sizeOf MonitorRacer)
          (imgui/aguafria_imgui_racer_size))
   (ak/== (ak/sizeOf MonitorSnapshot)
          (imgui/aguafria_imgui_snapshot_size))))

(az/defn initialize!
  "Attach ImGui to the existing GLFW/Vulkan objects without taking ownership."
  :-
  :bool
  []
  (when initialized
    (ak/return true))
  (let [interop (renderer/renderer-interop)]
    (when (or (ak/! (az/field interop valid))
              (ak/! (abi-valid?)))
      (ak/return false))
    (when (ak/! (imgui/aguafria_imgui_initialize
                 (desktop/window-address)
                 (az/field interop instance)
                 (az/field interop physical_device)
                 (az/field interop device)
                 (az/field interop queue)
                 (az/field interop queue_family)
                 (az/field interop render_pass)
                 (az/field interop image_count)))
      (ak/return false))
    (renderer/set-overlay-renderer!
     (ak/& imgui/aguafria_imgui_render))
    (set! initialized true)
    (refresh!)
    true))

(az/defn set-visible!
  :-
  :void
  [[visible :bool]]
  (imgui/aguafria_imgui_set_visible visible))

(az/defn toggle-visible!
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (imgui/aguafria_imgui_toggle_visible))

(az/defn set-raw-protocol-visible!
  "Raw prompt bytes and token IDs remain opt-in."
  :-
  :void
  [[visible :bool]]
  (imgui/aguafria_imgui_set_raw_protocol visible))

(az/defn raw-protocol-visible?
  "Whether the user explicitly enabled the technical protocol panel."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (imgui/aguafria_imgui_raw_protocol_visible))

(az/defn active?
  "Whether ImGui currently borrows the live renderer."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  initialized)

(az/defn visible?
  "Whether the F2-toggleable ImGui window is currently visible."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (imgui/aguafria_imgui_is_visible))

(az/defn shutdown!
  "Detach the overlay before its borrowed Vulkan objects are destroyed."
  :-
  :void
  []
  (when initialized
    (renderer/set-overlay-renderer! null)
    (renderer/renderer-wait-idle!)
    (imgui/aguafria_imgui_shutdown)
    (set! initialized false)))

(az/defn run!
  "Native desktop loop with the human-readable cognition monitor."
  :-
  :bool
  []
  (when (ak/! (desktop/initialize!))
    (ak/return false))
  (defer (desktop/shutdown!))
  (when (ak/! (initialize!))
    (ak/return false))
  (defer (shutdown!))
  (ak/while (desktop/should-run?)
    (refresh!)
    (set! _ (desktop/frame!)))
  true)

(clojure.core/defn status
  "Clojure-friendly monitor state with all eight nested racer rows decoded."
  []
  (assoc (dissoc (az/value (monitor-snapshot)) :racers)
         :active (active?)
         :visible (visible?)
         :raw-protocol-visible (raw-protocol-visible?)
         :overlay-installed (renderer/overlay-installed?)
         :racers (mapv #(az/value (monitor-racer %)) (range 8))
         :team-radios
         (mapv (fn [team-id]
                 (mapv #(az/value (monitor-radio-entry team-id %))
                       (range (monitor-radio-count team-id))))
               (range 4))
         :histories
         (mapv (fn [identifier]
                 (mapv #(az/value (monitor-history-entry identifier %))
                       (range (monitor-history-count identifier))))
               (range 8))))
