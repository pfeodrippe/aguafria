(ns racing-game.monitor
  "Dear ImGui cognition monitor for development and the demonstrator release.

  The game, model workers, Flecs world, and Vulkan renderer remain the same
  native code; this module only projects bounded public telemetry into ImGui."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.c :as std-c]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.fmt :as std-fmt]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.desktop :as desktop]
            [racing-game.render3d :as render3d]
            [racing-game.simulation :as simulation]
            [racing-game.vehicle-driver :as driver]
            [racing-game.telemetry :as telemetry]
            [racing-game.worker :as worker]
            [racing-game.protocol :as protocol]
            [aguafria-examples-native.imgui-bindings]
            [aguafria-examples-native.bindings.imgui :as imgui]
            [aguafria-examples-native.imgui-controls]
            [aguafria-examples-native.bindings.imgui-controls :as ui]))

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
   [:racers [:array simulation/racer-count MonitorRacer]]
   [:history_counts [:array simulation/racer-count :u8]]
   [:history [:array (* simulation/racer-count 64) MonitorRacer]]
   [:radio_counts [:array simulation/team-count :u8]]
   [:radio [:array (* simulation/team-count 32) MonitorRadio]]])

(az/defvar initialized false)

(az/defvar snapshot MonitorSnapshot
  (std-mem/zeroes (az/type MonitorSnapshot)))

(az/defvar history-raw-visible false)

(az/defn progress-bin
  :-
  :u8
  [[progress :f32]]
  (ak/as :u8
         (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 progress) 10.0)))))

(az/defn speed-bin
  :-
  :u8
  [[speed :f32]]
  (ak/as :u8
         (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 speed) 100.0)))))

(az/defn lane-choice
  :-
  :u8
  [[lane-target :f32]]
  (cond
    (< lane-target -0.025) 0
    (> lane-target 0.025) 2
    :else 1))

(az/defn pace-choice
  :-
  :u8
  [[target-speed :f32]]
  (cond
    (< target-speed 0.076) 0
    (< target-speed 0.084) 1
    :else 2))

(az/defn refresh-racer!
  "Copy one bounded semantic decision into the current row or history ABI."
  :-
  :void
  [[identifier :u8]
   [offset :usize]
   [destination :usize]
   [history-row :bool]
   [include-raw :bool]]
  (let [index (ak/as :usize (ak/intCast identifier))
        view (simulation/racer-view identifier)
        retirement (simulation/retirement-view index)
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
          ;; Stable display ABI: state 4 denotes DNF, not a fictional pit visit.
          :pit_state (if (and (ak/! history-row) (az/field retirement retired))
                       4 (az/field view pit_state))
          :pit_stops (az/field view pit_stops)
          :damage_stage (az/field view damage_stage)
          :radio_code (az/field view radio_code)
          :radio_source (az/field view radio_source)
          :team_instruction (az/field view team_instruction)
          :team_pending (az/field view team_pending)
          :source (az/field entry source)
          :rank (if history-row (az/field entry rank) (az/field view rank))
          :item (if history-row (az/field entry item) (az/field view item))
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
          :lap (if history-row (az/field entry lap)
                   (if (az/field retirement retired) (az/field retirement lap) (az/field view lap)))
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
          :progress (if history-row (az/field entry progress)
                        (if (az/field retirement retired)
                          (az/field retirement progress) (az/field view progress)))
          :speed (if history-row (az/field entry speed) (az/field view speed))
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
  :-
  :void
  []
  (let [command (imgui/aguafria_imgui_camera_command)]
    (cond
      (and (> command 0) (<= command 5))
      (render3d/camera-preset! (ak/intCast (- command 1)))
      (ak/== command 6) (render3d/zoom-by! 0.8)
      (ak/== command 7) (render3d/zoom-by! 1.25)
      (and (>= command 100) (< command (+ 100 simulation/racer-count)))
      (render3d/select-camera! (ak/intCast (- command 100)) true)))
  (imgui/aguafria_imgui_camera_status render3d/camera-mode render3d/zoom-multiplier)
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
    (dotimes [identifier simulation/racer-count]
      (refresh-racer! (ak/intCast identifier) 0 identifier false include-raw))
    (dotimes [team-id simulation/team-count]
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
      (dotimes [identifier simulation/racer-count]
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
  :-
  MonitorSnapshot
  []
  snapshot)

(az/defn monitor-racer
  "Inspect one decoded monitor row without exposing nested ABI bytes."
  :-
  MonitorRacer
  [[identifier :u8]]
  (if (< identifier simulation/racer-count)
    (az/index (az/field snapshot racers) (ak/intCast identifier))
    (std-mem/zeroes (az/type MonitorRacer))))

(az/defn monitor-history-count
  "Number of retained native decisions exposed for one racer."
  :-
  :u8
  [[identifier :u8]]
  (if (< identifier simulation/racer-count)
    (az/index (az/field snapshot history_counts) (ak/intCast identifier))
    0))

(az/defn monitor-history-entry
  "Inspect one newest-first decision from a racer's native telemetry ring."
  :-
  MonitorRacer
  [[identifier :u8]
   [offset :u8]]
  (if (and (< identifier simulation/racer-count)
           (< offset (monitor-history-count identifier)))
    (az/index (az/field snapshot history)
              (+ (* (ak/as :usize (ak/intCast identifier)) 64)
                 (ak/as :usize (ak/intCast offset))))
    (std-mem/zeroes (az/type MonitorRacer))))

(az/defn monitor-radio-count
  "Number of semantic exchanges visible for one team in the current UI state."
  :-
  :u8
  [[team-id :u8]]
  (if (< team-id simulation/team-count)
    (az/index (az/field snapshot radio_counts) (ak/intCast team-id))
    0))

(az/defn monitor-radio-entry
  "Inspect one newest-first team/driver exchange exactly as shown in ImGui."
  :-
  MonitorRadio
  [[team-id :u8]
   [offset :u8]]
  (if (and (< team-id simulation/team-count) (< offset (monitor-radio-count team-id)))
    (az/index (az/field snapshot radio)
              (+ (* (ak/as :usize (ak/intCast team-id)) 32)
                 (ak/as :usize (ak/intCast offset))))
    (std-mem/zeroes (az/type MonitorRadio))))

(az/defn abi-valid?
  "Verify the generated Zig structs exactly match their C++ ABI."
  :-
  :bool
  []
  (and
   (ak/== (ak/sizeOf MonitorRacer)
          (imgui/aguafria_imgui_racer_size))
   (ak/== (ak/sizeOf MonitorSnapshot)
          (imgui/aguafria_imgui_snapshot_size))))

(az/defvar language-history-racer :i32 -1)

(az/defvar language-history-follow :u8 1)

(az/defvar language-history-instructions :u8 0)

(az/defvar language-history-end :u64 0)

(az/defvar language-history-every-call :u8 0)

(az/defn same-language-exchange?
  "Group only identical adjacent content/outcomes, never timing or sequence IDs.
  Actor, race and instructions remain boundaries even when hidden in the UI."
  :- :bool [[a simulation/LanguageExchange] [b simulation/LanguageExchange]]
  (let [ar (az/field (az/field a result) request)
        br (az/field (az/field b result) request)
        ag (az/field (az/field a result) generation)
        bg (az/field (az/field b result) generation)]
    (and (az/field a valid) (az/field b valid)
         (ak/== (az/field a reason) (az/field b reason))
         (ak/== (az/field ar actor) (az/field br actor))
         (ak/== (az/field ar epoch) (az/field br epoch))
         (ak/== (az/field ag valid) (az/field bg valid))
         (ak/== (az/field ag stop) (az/field bg stop))
         (std-mem/eql :u8 (az/slice (az/field ar system_bytes) 0 (ak/min 160 (az/field ar system_byte_count)))
                          (az/slice (az/field br system_bytes) 0 (ak/min 160 (az/field br system_byte_count))))
         (std-mem/eql :u8 (az/slice (az/field ar prompt_bytes) 0 (ak/min 160 (az/field ar prompt_byte_count)))
                          (az/slice (az/field br prompt_bytes) 0 (ak/min 160 (az/field br prompt_byte_count))))
         (std-mem/eql :u8 (az/slice (az/field ag bytes) 0 (ak/min 2048 (az/field ag byte_count)))
                          (az/slice (az/field bg bytes) 0 (ak/min 2048 (az/field bg byte_count)))))))

(az/defn history-text!
  "Length-delimited UTF-8, including model output: no format evaluation or NUL requirement."
  :- :void [[text [:slice-const :u8]] [r :f32] [g :f32] [b :f32]]
  (ui/aguafria_ui_wrapped_text (az/field text ptr) (az/field text len) r g b))

(az/defn draw-language-exchange!
  "Render one real native exchange. Validation is not a claim of tactical quality."
  :- :void [[entry simulation/LanguageExchange]]
  (let [result (az/field entry result)
        request (az/field result request)
        generation (az/field result generation)
        tint (render3d/racer-tint (az/field request actor))
        ^{:var [:array 512 :u8]} buffer ak/undefined
        header (catch (std-fmt/bufPrint (ak/& buffer)
                        "R{d} | decision #{d} | race {d} | {s}\nInput {d} tokens, output {d} tokens | inference {d:.1} ms + queue {d:.1} ms = {d:.1} ms total"
                        [(az/field request actor) (az/field entry sequence) (az/field request epoch)
                         (protocol/driving-plan-rejection (az/field entry reason))
                         (az/field generation input_tokens) (az/field generation output_tokens)
                         (/ (ak/as :f64 (ak/floatFromInt (az/field result inference_us))) 1000.0)
                         (/ (ak/as :f64 (ak/floatFromInt (az/field result queue_us))) 1000.0)
                         (/ (ak/as :f64 (ak/floatFromInt (az/field result total_us))) 1000.0)])
                      (ak/return))]
    (ui/aguafria_ui_separator)
    (history-text! header (az/field tint x) (az/field tint y) (az/field tint z))
    (when (ak/!= language-history-instructions 0)
      (history-text! "Instructions sent:" 0.75 0.75 0.75)
      (history-text! (az/slice (az/field request system_bytes) 0
                              (ak/min 160 (az/field request system_byte_count))) 0.85 0.85 0.85))
    (history-text! "Observation sent:" 0.75 0.75 0.75)
    (history-text! (az/slice (az/field request prompt_bytes) 0
                            (ak/min 160 (az/field request prompt_byte_count))) 1.0 1.0 1.0)
    (history-text! "Model replied:" 0.75 0.75 0.75)
    (if (> (az/field generation byte_count) 0)
      (history-text! (az/slice (az/field generation bytes) 0
                              (ak/min (az/field (az/field generation bytes) len)
                                      (az/field generation byte_count)))
                     (az/field tint x) (az/field tint y) (az/field tint z))
      (history-text! "(No text returned)" 0.9 0.65 0.35))))

(az/defn draw-language-group!
  :- :void [[latest simulation/LanguageExchange] [oldest :u64] [count :usize]]
  (draw-language-exchange! latest)
  (when (> count 1)
    (let [^{:var [:array 192 :u8]} buffer ak/undefined
          text (catch (std-fmt/bufPrint (ak/& buffer)
                        "Unchanged across {d} calls (#{d}-#{d}). Timings/token counts above are for the latest call."
                        [count oldest (az/field latest sequence)]) (ak/return))]
      (history-text! text 1.0 0.8 0.25))))

(az/defn draw-language-history!
  "F2 opens bounded, scrollable exact driver text history in the game itself."
  :- :void []
  (when (ak/! (imgui/aguafria_imgui_is_visible))
    (ak/return))
  ;; Both histories remain available, but do not open stacked translucent
  ;; windows on first use. The user can expand the old team's monitor title.
  (ui/aguafria_ui_collapse_window_once "Aguafria racer cognition")
  (let [visible (ui/aguafria_ui_window_begin "Driver text history - F2" 850.0 560.0)]
    (ak/defer (ui/aguafria_ui_window_end))
    (when (ak/== visible 0) (ak/return))
    (history-text! "Experimental driver AI. Accepted means valid and installed, NOT a good decision. Team text is not connected here yet." 1.0 0.8 0.25)
    (let [^{:var [:array 128 :u8]} buffer ak/undefined
          status (catch (std-fmt/bufPrint (ak/& buffer)
                          "Current race: {d}. Retained replies below may belong to earlier races."
                          [simulation/race-epoch]) (ak/return))]
      (history-text! status 0.85 0.85 0.85))
    (when (ak/!= (ui/aguafria_ui_button "All racers") 0)
      (set! language-history-racer -1))
    (dotimes [identifier simulation/racer-count]
      (let [^{:var [:array 12 :u8]} buffer ak/undefined
            label (catch (std-fmt/bufPrintZ (ak/& buffer) "R{d}" [identifier]) (ak/return))]
        (when (ak/!= (mod identifier 10) 0)
          (ui/aguafria_ui_same_line))
        (when (ak/!= (ui/aguafria_ui_button (az/field label ptr)) 0)
          (set! language-history-racer (ak/intCast identifier)))))
    (set! _ (ui/aguafria_ui_checkbox "Follow newest (uncheck to read older replies)" (ak/& language-history-follow)))
    (set! _ (ui/aguafria_ui_checkbox "Show exact instructions too" (ak/& language-history-instructions)))
    (set! _ (ui/aguafria_ui_checkbox "Show every call (including unchanged replies)" (ak/& language-history-every-call)))
    (when (>= language-history-racer 0)
      (let [identifier (ak/as :usize (ak/intCast language-history-racer))
            enabled (az/field (az/index simulation/language-drivers identifier) enabled)
            ^{:var [:array 96 :u8]} buffer ak/undefined
            status (catch (std-fmt/bufPrint (ak/& buffer) "Showing R{d}. Plain-English driver: {s}."
                            [identifier (if enabled "enabled" "disabled")]) (ak/return))]
        (history-text! status 1.0 1.0 1.0)
        (when (ak/!= (ui/aguafria_ui_button (if enabled "Disable text driver" "Enable text driver")) 0)
          (set! _ (simulation/request-language-mode! identifier (ak/! enabled))))))
    (when (ak/!= language-history-follow 0)
      (set! language-history-end (simulation/language-exchange-count)))
    ;; Keep selection/follow controls available while only the entries scroll.
    ;; EndChild is required even when BeginChild reports a clipped region.
    (let [entries-visible (ui/aguafria_ui_scroll_begin "driver-exchanges" language-history-follow)]
      (ak/defer (ui/aguafria_ui_scroll_end))
      (when (ak/== entries-visible 0) (ak/return))
      (let [end language-history-end
            ^:var pending (std-mem/zeroes (az/type simulation/LanguageExchange))
            ^{:var :usize} count 0
            ^{:var :u64} oldest 0]
        (dotimes [offset (ak/min end 128)]
          (let [entry (simulation/language-exchange-at (- end offset))]
            (when (and (az/field entry valid)
                       (or (< language-history-racer 0)
                           (ak/== (az/field (az/field (az/field entry result) request) actor)
                                  language-history-racer)))
              (if (and (> count 0) (ak/== language-history-every-call 0)
                       (same-language-exchange? pending entry))
                (set! count (+ count 1))
                (do
                  (when (> count 0) (draw-language-group! pending oldest count))
                  (set! pending entry)
                  (set! count 1)))
              (set! oldest (az/field entry sequence)))))
        (when (> count 0) (draw-language-group! pending oldest count))
        (when (ak/== count 0)
          (history-text! "No retained text exchanges for this selection. Select a racer and enable its text driver. First reply may take several seconds. Frozen entries eventually expire from the 128-entry history." 0.85 0.85 0.85))))))

(az/defvar frame-intervals [:array 120 :f64]
  (std-mem/zeroes (az/type [:array 120 :f64])))

(az/defvar frame-interval-index :usize 0)

(az/defvar frame-interval-count :usize 0)

(az/defvar frame-interval-sum :f64 0.0)

(az/defvar frame-previous-time :f64 0.0)

(az/defvar frame-report-enabled false)

(az/defvar frame-report-time :f64 0.0)

(az/defvar frame-report-tick :u64 0)

(az/defn measured-fps
  "Actual render cadence across the last 120 intervals, including presentation
  waits. This is not the fixed physics rate or the target frame rate."
  :- :f64 []
  (if (> frame-interval-sum 0.0)
    (/ (ak/as :f64 (ak/floatFromInt frame-interval-count)) frame-interval-sum)
    0.0))

(az/defn draw-frame-rate!
  "Always-visible measured FPS, in the game rather than the optional log view."
  :- :void []
  (let [now (glfw/glfwGetTime)]
    (when (> frame-previous-time 0.0)
      (let [interval (- now frame-previous-time)]
        (set! frame-interval-sum
              (+ (- frame-interval-sum (az/index frame-intervals frame-interval-index)) interval))
        (set! (az/index frame-intervals frame-interval-index) interval)
        (set! frame-interval-index (mod (+ frame-interval-index 1) 120))
        (set! frame-interval-count (ak/min 120 (+ frame-interval-count 1)))))
    (set! frame-previous-time now))
  ;; Optional console mirror allows measuring the exact standalone executable
  ;; without a REPL, debugger or a screenshot-derived FPS estimate.
  (when (and frame-report-enabled (>= (- frame-previous-time frame-report-time) 5.0))
    (let [tick (az/field (simulation/snapshot) tick)
          elapsed (- frame-previous-time frame-report-time)
          fps (measured-fps)]
      (std-debug/print
        "PERF {d:.2} FPS | {d:.2} ms/frame | {d:.2} physics Hz | {d} instance draws | {d} instances | {d} mesh-upload bytes\n"
        [fps (if (> fps 0.0) (/ 1000.0 fps) 0.0)
         (/ (ak/as :f64 (ak/floatFromInt (- tick (ak/min tick frame-report-tick)))) elapsed)
         renderer/instance-draws renderer/instance-stream-used renderer/instance-upload-bytes])
      (set! frame-report-time frame-previous-time)
      (set! frame-report-tick tick)))
  (let [fps (measured-fps)
        ^{:var [:array 128 :u8]} buffer ak/undefined
        label (catch (std-fmt/bufPrintZ (ak/& buffer)
                       "{d:.1} FPS | {d:.2} ms/frame\nTarget 120 FPS | last {d} frames"
                       [fps (if (> fps 0.0) (/ 1000.0 fps) 0.0) frame-interval-count])
                     (ak/return))
        visible (imgui/aguafria_imgui_panel_begin "##frame-rate" 290.0 0.5 0.16)]
    (ak/defer (imgui/aguafria_imgui_panel_end))
    (when visible
      (imgui/aguafria_imgui_label (az/field label ptr) 1.0 1.0 1.0))))

(az/defn draw-driving-telemetry!
  "Always-visible selected-driver telemetry. Reads the final pedal commands
  actually sent to physics, not an AI observation or desired speed as a proxy.
  Runs inside the existing ImGui frame through the generic drawing callback."
  {:attrs #{:export}}
  :- :void []
  (draw-frame-rate!)
  (ak/defer (draw-language-history!))
  (let [id (if render3d/follow-front-pack
             (az/field (simulation/snapshot) leader)
             render3d/camera-racer)
        racer (simulation/racer-view id)
        control (az/index simulation/vehicle-controls id)
        recovery (simulation/recovery-view id)
        turnaround (simulation/turnaround-view id)
        retired (az/field (simulation/retirement-view id) retired)
        overturned (driver/overturned? (simulation/vehicle-pose id 0))
        tint (render3d/racer-tint id)
        ^{:zig/type [:slice-const :u8]} mode
               (cond overturned "OVERTURNED / PROPULSION CUT"
                     retired "RETIRED"
                     (ak/!= simulation/race-state simulation/race-state-running) "RACE STOPPED"
                     (az/field racer finished) "FINISHED / 108 km/h COOLDOWN"
                     (az/field (az/field turnaround state) active) "TURNAROUND"
                     (ak/!= (az/field (az/field recovery state) phase) 0) "RECOVERY"
                     (> (az/field racer pit_state) simulation/pit-state-called) "PIT LANE / SERVICE"
                     :else "RACING")
        ^{:zig/type [:slice-const :u8]} gear
               (cond (< (az/field recovery gear) 0) "REVERSE"
                     (ak/== (az/field recovery gear) 0) "NEUTRAL"
                     :else "FORWARD")
        ^{:var [:array 512 :u8]} buffer ak/undefined
        text (catch (std-fmt/bufPrintZ (ak/& buffer)
                      "R{d}  LIVE DRIVING\n{d:.0} km/h\nThrottle {d:.0}%   Brake {d:.0}%\nSteering {d:.1} deg\nGear: {s}\n{s}\nAI cruise request: {d:.0} km/h\nCorner planner cap: {d:.0} km/h"
                      [id (* (az/field racer speed) 3600.0)
                       (* (az/field control throttle) 100.0)
                       (* (az/field control brake) 100.0)
                       (* (az/field control steering) 57.29578) gear mode
                       (* (az/field racer target_speed) 3600.0)
                       (* (driver/braking-envelope (* (az/field racer progress) 4309.0)
                                                   (* (az/field racer speed) 1000.0)) 3.6)])
                    (ak/return))]
    (let [visible (imgui/aguafria_imgui_panel_begin "##driving-telemetry" 280.0 0.99 0.94)]
      (ak/defer (imgui/aguafria_imgui_panel_end))
      (when visible
        (imgui/aguafria_imgui_label (az/field text ptr)
          (az/field tint x) (az/field tint y) (az/field tint z))
        (imgui/aguafria_imgui_meter "THROTTLE" (az/field control throttle) 0.15 0.85 0.35)
        (imgui/aguafria_imgui_meter "BRAKE" (az/field control brake) 0.95 0.22 0.18)))))

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
    (imgui/aguafria_imgui_set_draw_callback (ak/& draw-driving-telemetry!))
    (set! initialized true)
    (refresh!)
    true))

(az/defn set-visible!
  :-
  :void
  [[visible :bool]]
  (imgui/aguafria_imgui_set_visible visible))

(az/defn toggle-visible!
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
  :-
  :bool
  []
  (imgui/aguafria_imgui_raw_protocol_visible))

(az/defn active?
  "Whether ImGui currently borrows the live renderer."
  :-
  :bool
  []
  initialized)

(az/defn visible?
  "Whether the F2-toggleable ImGui window is currently visible."
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
    (imgui/aguafria_imgui_set_draw_callback ak/null)
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
  (set! frame-report-enabled
    (let [value (std-c/getenv "RACING_FPS_LOG")]
      (if (ak/!= value ak/null)
        (std-mem/eql :u8 (std-mem/span (az/unwrap value)) "1")
        false)))
  (defer (shutdown!))
  (ak/while (desktop/should-run?)
    (refresh!)
    (set! _ (desktop/frame!)))
  true)

(clojure.core/defn status
  "Clojure-friendly monitor state with all racer rows decoded."
  []
  (assoc (dissoc (az/value (monitor-snapshot)) :racers)
         :active (active?)
         :visible (visible?)
         :raw-protocol-visible (raw-protocol-visible?)
         :overlay-installed (renderer/overlay-installed?)
         :racers (mapv #(az/value (monitor-racer %)) (range (az/value simulation/racer-count)))
         :team-radios
         (mapv (fn [team-id]
                 (mapv #(az/value (monitor-radio-entry team-id %))
                       (range (monitor-radio-count team-id))))
               (range (az/value simulation/team-count)))
         :histories
         (mapv (fn [identifier]
                 (mapv #(az/value (monitor-history-entry identifier %))
                       (range (monitor-history-count identifier))))
               (range (az/value simulation/racer-count)))))
