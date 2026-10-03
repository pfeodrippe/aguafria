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
            [aguafria.zig :as a]
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

(a/defstruct MonitorRacer
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

(a/defstruct MonitorRadio
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

(a/defstruct MonitorSnapshot
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

(a/defvar initialized false)

(a/defvar snapshot MonitorSnapshot
  (std-mem/zeroes (a/type MonitorSnapshot)))

(a/defvar history-raw-visible false)

(a/defn progress-bin :u8
  [[progress :f32]]
  (ak/as (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 progress) 10.0)))
         :u8))

(a/defn speed-bin :u8
  [[speed :f32]]
  (ak/as (ak/intFromFloat
          (ak/min 9.0 (* (ak/max 0.0 speed) 100.0)))
         :u8))

(a/defn lane-choice :u8
  [[lane-target :f32]]
  (cond
    (< lane-target -0.025) 0
    (> lane-target 0.025) 2
    :else 1))

(a/defn pace-choice :u8
  [[target-speed :f32]]
  (cond
    (< target-speed 0.076) 0
    (< target-speed 0.084) 1
    :else 2))

(a/defn refresh-racer! :void
  "Copy one bounded semantic decision into the current row or history ABI."
  [[identifier :u8]
   [offset :usize]
   [destination :usize]
   [history-row :bool]
   [include-raw :bool]]
  (let [index (ak/as (ak/intCast identifier) :usize)
        view (simulation/racer-view identifier)
        retirement (simulation/retirement-view index)
        entry (telemetry/entry-at identifier offset)
        outcome (telemetry/outcome-at identifier offset)
        detailed
        (and (a/field entry valid)
             (ak/== (a/field entry source) telemetry/source-llm)
             (> (a/field entry prompt_byte_count) 0))
        ^:var row
        (MonitorRacer
         {:valid (if history-row
                   (a/field entry valid)
                   (a/field view valid))
          :detailed_observation detailed
          :urgent (a/field entry urgent)
          :pending (if history-row false (a/field view pending))
          :accepted (a/field entry accepted)
          :outcome_resolved (a/field outcome resolved)
          :outcome_item_used (a/field outcome item_used)
          :id identifier
          :team (a/field view team)
          :teammate (a/field view teammate)
          ;; Stable display ABI: state 4 denotes DNF, not a fictional pit visit.
          :pit_state (if (and (ak/! history-row) (a/field retirement retired))
                       4 (a/field view pit_state))
          :pit_stops (a/field view pit_stops)
          :damage_stage (a/field view damage_stage)
          :radio_code (a/field view radio_code)
          :radio_source (a/field view radio_source)
          :team_instruction (a/field view team_instruction)
          :team_pending (a/field view team_pending)
          :source (a/field entry source)
          :rank (if history-row (a/field entry rank) (a/field view rank))
          :item (if history-row (a/field entry item) (a/field view item))
          :target (a/field entry target)
          :persona 0
          :target_lane 1
          :tactical_status 0
          :lane_choice (lane-choice (a/field entry lane_target))
          :pace_choice (pace-choice (a/field entry target_speed))
          :item_choice (if (> (a/field entry action) 0) 1 0)
          :deadline_status (a/field entry deadline_status)
          :start_rank (a/field outcome start_rank)
          :end_rank (a/field outcome end_rank)
          :lap (if history-row (a/field entry lap)
                   (if (a/field retirement retired) (a/field retirement lap) (a/field view lap)))
          :hits_dealt (a/field outcome hits_dealt)
          :progress_bin (progress-bin (a/field entry progress))
          :speed_bin (speed-bin (a/field entry speed))
          :target_distance_bin 0
          :model_step_count
          (ak/intCast (ak/min (a/field entry input_token_count) 255))
          :revision (a/field entry revision)
          :radio_revision (a/field view radio_revision)
          :team_decision_revision (a/field view team_decision_revision)
          :team_decisions (a/field view team_decisions)
          :team_last_latency_us (a/field view team_last_latency_us)
          :team_average_latency_us (a/field view team_average_latency_us)
          :decisions (a/field view decisions)
          :deadline_misses (a/field view deadline_misses)
          :pending_age_ticks (a/field view pending_age_ticks)
          :queue_us (a/field entry queue_us)
          :total_us (a/field entry total_us)
          :progress (if history-row (a/field entry progress)
                        (if (a/field retirement retired)
                          (a/field retirement progress) (a/field view progress)))
          :speed (if history-row (a/field entry speed) (a/field view speed))
          :steps_per_second (a/field entry tokens_per_second)
          :progress_gain (a/field outcome progress_gain)
          :tire_condition (a/field view tire_condition)
          :damage (a/field view damage)
          :pit_seconds (a/field view pit_seconds)
          :prompt (std-mem/zeroes (a/type [:array 161 :u8]))
          :response (std-mem/zeroes (a/type [:array 2 :u8]))
          :input_tokens (std-mem/zeroes (a/type [:array 8 :u32]))
          :input_token_count 0
          :output_token 0})]
    ;; The human-readable observation is part of the normal monitor. Numeric
    ;; token IDs and constrained output remain behind the explicit raw toggle.
    (when detailed
      (let [prompt-count
            (ak/min worker/prompt-capacity
                    (ak/as (ak/intCast (a/field entry prompt_byte_count))
                           :usize))]
        (dotimes [position prompt-count]
          (ak/= (a/index (a/field row prompt) position)
                (a/index (a/field entry prompt_bytes) position)))))
    (when (and include-raw detailed)
      (let [input-count
            (ak/min (ak/as (ak/intCast (a/field entry input_token_count)) :usize)
                    8)]
        (when (> (a/field entry response_byte_count) 0)
          (ak/= (a/index (a/field row response) 0)
                (a/index (a/field entry response_bytes) 0)))
        (dotimes [position input-count]
          (ak/= (a/index (a/field row input_tokens) position)
                (a/index (a/field entry input_tokens) position)))
        (ak/= (a/field row input_token_count)
              (a/field entry input_token_count))
        (when (> (a/field entry output_token_count) 0)
          (ak/= (a/field row output_token)
                (a/index (a/field entry output_tokens) 0)))))
    (if history-row
      (ak/= (a/index (a/field snapshot history) destination) row)
      (ak/= (a/index (a/field snapshot racers) index) row))))

(a/defn refresh-radio! :void
  "Copy one semantic newest-first team exchange into the stable C ABI."
  [[team-id :u8]
   [offset :usize]
   [destination :usize]]
  (let [entry (simulation/team-radio-entry team-id offset)
        prompt-count
        (ak/min worker/prompt-capacity
                (ak/as (ak/intCast (a/field entry prompt_byte_count))
                       :usize))
        ^:var row
        (MonitorRadio
         {:valid (a/field entry valid)
          :team (a/field entry team)
          :source (a/field entry source)
          :target (a/field entry target)
          :code (a/field entry code)
          :pit_state (a/field entry pit_state)
          :instruction (a/field entry instruction)
          :reserved 0
          :model_accepted (a/field entry model_accepted)
          :model_action (a/field entry model_action)
          :prompt_byte_count (ak/intCast prompt-count)
          :input_token_count (a/field entry input_token_count)
          :best_token (a/field entry best_token)
          :tick (a/field entry tick)
          :decision_revision (a/field entry decision_revision)
          :latency_us (a/field entry latency_us)
          :tokens_per_second (a/field entry tokens_per_second)
          :tire_condition (a/field entry tire_condition)
          :damage (a/field entry damage)
          :prompt (std-mem/zeroes (a/type [:array 161 :u8]))})]
    (dotimes [position prompt-count]
      (ak/= (a/index (a/field row prompt) position)
            (a/index (a/field entry prompt_bytes) position)))
    (ak/= (a/index (a/field snapshot radio) destination) row)))

(a/defn refresh! :void
  "Refresh the allocation-free native snapshot consumed by Dear ImGui."
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
        (or (ak/!= (a/field snapshot total_decisions)
                   (a/field cognition total_entries))
            (ak/!= (a/field snapshot resolved_outcomes)
                   (a/field cognition resolved_outcomes))
            (ak/!= history-raw-visible include-raw))]
    (ak/= (a/field snapshot tick) (a/field race tick))
    (ak/= (a/field snapshot total_decisions)
          (a/field cognition total_entries))
    (ak/= (a/field snapshot llm_decisions) (a/field cognition llm_entries))
    (ak/= (a/field snapshot fallback_decisions)
          (a/field cognition fallback_entries))
    (ak/= (a/field snapshot rejected_decisions)
          (a/field cognition rejected_entries))
    (ak/= (a/field snapshot deadline_misses)
          (a/field cognition deadline_misses))
    (ak/= (a/field snapshot resolved_outcomes)
          (a/field cognition resolved_outcomes))
    (ak/= (a/field snapshot worker_requests) (a/field workers requests))
    (ak/= (a/field snapshot worker_results) (a/field workers results))
    (ak/= (a/field snapshot worker_state_bytes)
          (ak/intCast (a/field workers state_bytes)))
    (ak/= (a/field snapshot pending_requests)
          (ak/intCast (a/field workers pending)))
    (ak/= (a/field snapshot average_steps_per_second)
          (a/field cognition average_tokens_per_second))
    (dotimes [identifier simulation/racer-count]
      (refresh-racer! (ak/intCast identifier) 0 identifier false include-raw))
    (dotimes [team-id simulation/team-count]
      (let [radio-count
            (ak/as (ak/intCast
                    (simulation/team-radio-history-count
                     (ak/intCast team-id)))
                   :usize)]
        (ak/= (a/index (a/field snapshot radio_counts) team-id)
              (ak/intCast radio-count))
        (dotimes [offset 32]
          (let [destination (+ (* team-id 32) offset)]
            (if (< offset radio-count)
              (refresh-radio! (ak/intCast team-id) offset destination)
              (ak/= (a/index (a/field snapshot radio) destination)
                    (std-mem/zeroes (a/type MonitorRadio))))))))
    (when history-changed
      (dotimes [identifier simulation/racer-count]
        (let [history-count
              (ak/as (ak/intCast
                      (ak/min (telemetry/decision-count (ak/intCast identifier))
                              telemetry/entries-per-racer))
                     :usize)]
          (ak/= (a/index (a/field snapshot history_counts) identifier)
                (ak/intCast history-count))
          (dotimes [offset 64]
            (let [destination (+ (* identifier 64) offset)]
              (if (< offset history-count)
                (refresh-racer! (ak/intCast identifier) offset destination
                                true include-raw)
                (ak/= (a/index (a/field snapshot history) destination)
                      (std-mem/zeroes (a/type MonitorRacer))))))))
      (ak/= history-raw-visible include-raw))
    (imgui/aguafria_imgui_update (ak/ptrCast (ak/& snapshot)))))

(a/defn monitor-snapshot MonitorSnapshot
  "Inspectable native snapshot used by the ImGui layer."
  []
  snapshot)

(a/defn monitor-racer MonitorRacer
  "Inspect one decoded monitor row without exposing nested ABI bytes."
  [[identifier :u8]]
  (if (< identifier simulation/racer-count)
    (a/index (a/field snapshot racers) (ak/intCast identifier))
    (std-mem/zeroes (a/type MonitorRacer))))

(a/defn monitor-history-count :u8
  "Number of retained native decisions exposed for one racer."
  [[identifier :u8]]
  (if (< identifier simulation/racer-count)
    (a/index (a/field snapshot history_counts) (ak/intCast identifier))
    0))

(a/defn monitor-history-entry MonitorRacer
  "Inspect one newest-first decision from a racer's native telemetry ring."
  [[identifier :u8]
   [offset :u8]]
  (if (and (< identifier simulation/racer-count)
           (< offset (monitor-history-count identifier)))
    (a/index (a/field snapshot history)
              (+ (* (ak/as (ak/intCast identifier) :usize) 64)
                 (ak/as (ak/intCast offset) :usize)))
    (std-mem/zeroes (a/type MonitorRacer))))

(a/defn monitor-radio-count :u8
  "Number of semantic exchanges visible for one team in the current UI state."
  [[team-id :u8]]
  (if (< team-id simulation/team-count)
    (a/index (a/field snapshot radio_counts) (ak/intCast team-id))
    0))

(a/defn monitor-radio-entry MonitorRadio
  "Inspect one newest-first team/driver exchange exactly as shown in ImGui."
  [[team-id :u8]
   [offset :u8]]
  (if (and (< team-id simulation/team-count) (< offset (monitor-radio-count team-id)))
    (a/index (a/field snapshot radio)
              (+ (* (ak/as (ak/intCast team-id) :usize) 32)
                 (ak/as (ak/intCast offset) :usize)))
    (std-mem/zeroes (a/type MonitorRadio))))

(a/defn abi-valid? :bool
  "Verify the generated Zig structs exactly match their C++ ABI."
  []
  (and
   (ak/== (ak/sizeOf MonitorRacer)
          (imgui/aguafria_imgui_racer_size))
   (ak/== (ak/sizeOf MonitorSnapshot)
          (imgui/aguafria_imgui_snapshot_size))))

(a/defvar language-history-racer :i32 -1)

(a/defvar language-history-follow :u8 1)

(a/defvar language-history-instructions :u8 0)

(a/defvar language-history-end :u64 0)

(a/defvar language-history-every-call :u8 0)

(a/defn same-language-exchange? :bool
  "Group only identical adjacent content/outcomes, never timing or sequence IDs.
  Actor, race and instructions remain boundaries even when hidden in the UI." [[a simulation/LanguageExchange] [b simulation/LanguageExchange]]
  (let [ar (a/field (a/field a result) request)
        br (a/field (a/field b result) request)
        ag (a/field (a/field a result) generation)
        bg (a/field (a/field b result) generation)]
    (and (a/field a valid) (a/field b valid)
         (ak/== (a/field a reason) (a/field b reason))
         (ak/== (a/field ar actor) (a/field br actor))
         (ak/== (a/field ar epoch) (a/field br epoch))
         (ak/== (a/field ag valid) (a/field bg valid))
         (ak/== (a/field ag stop) (a/field bg stop))
         (std-mem/eql :u8 (a/slice (a/field ar system_bytes) 0 (ak/min 160 (a/field ar system_byte_count)))
                          (a/slice (a/field br system_bytes) 0 (ak/min 160 (a/field br system_byte_count))))
         (std-mem/eql :u8 (a/slice (a/field ar prompt_bytes) 0 (ak/min 160 (a/field ar prompt_byte_count)))
                          (a/slice (a/field br prompt_bytes) 0 (ak/min 160 (a/field br prompt_byte_count))))
         (std-mem/eql :u8 (a/slice (a/field ag bytes) 0 (ak/min 2048 (a/field ag byte_count)))
                          (a/slice (a/field bg bytes) 0 (ak/min 2048 (a/field bg byte_count)))))))

(a/defn history-text! :void
  "Length-delimited UTF-8, including model output: no format evaluation or NUL requirement." [[text [:slice-const :u8]] [r :f32] [g :f32] [b :f32]]
  (ui/aguafria_ui_wrapped_text (a/field text ptr) (a/field text len) r g b))

(a/defn draw-language-exchange! :void
  "Render one real native exchange. Validation is not a claim of tactical quality." [[entry simulation/LanguageExchange]]
  (let [result (a/field entry result)
        request (a/field result request)
        generation (a/field result generation)
        tint (render3d/racer-tint (a/field request actor))
        ^:var buffer (ak/as ak/undefined [:array 512 :u8])
        header (catch (std-mem/print (ak/& buffer)
                        "R{d} | decision #{d} | race {d} | {s}\nInput {d} tokens, output {d} tokens | inference {d:.1} ms + queue {d:.1} ms = {d:.1} ms total"
                        [(a/field request actor) (a/field entry sequence) (a/field request epoch)
                         (protocol/driving-plan-rejection (a/field entry reason))
                         (a/field generation input_tokens) (a/field generation output_tokens)
                         (/ (ak/as (ak/floatFromInt (a/field result inference_us)) :f64) 1000.0)
                         (/ (ak/as (ak/floatFromInt (a/field result queue_us)) :f64) 1000.0)
                         (/ (ak/as (ak/floatFromInt (a/field result total_us)) :f64) 1000.0)])
                      (ak/return))]
    (ui/aguafria_ui_separator)
    (history-text! header (a/field tint x) (a/field tint y) (a/field tint z))
    (when (ak/!= language-history-instructions 0)
      (history-text! "Instructions sent:" 0.75 0.75 0.75)
      (history-text! (a/slice (a/field request system_bytes) 0
                              (ak/min 160 (a/field request system_byte_count))) 0.85 0.85 0.85))
    (history-text! "Observation sent:" 0.75 0.75 0.75)
    (history-text! (a/slice (a/field request prompt_bytes) 0
                            (ak/min 160 (a/field request prompt_byte_count))) 1.0 1.0 1.0)
    (history-text! "Model replied:" 0.75 0.75 0.75)
    (if (> (a/field generation byte_count) 0)
      (history-text! (a/slice (a/field generation bytes) 0
                              (ak/min (a/field (a/field generation bytes) len)
                                      (a/field generation byte_count)))
                     (a/field tint x) (a/field tint y) (a/field tint z))
      (history-text! "(No text returned)" 0.9 0.65 0.35))))

(a/defn draw-language-group! :void [[latest simulation/LanguageExchange] [oldest :u64] [count :usize]]
  (draw-language-exchange! latest)
  (when (> count 1)
    (let [^:var buffer (ak/as ak/undefined [:array 192 :u8])
          text (catch (std-mem/print (ak/& buffer)
                        "Unchanged across {d} calls (#{d}-#{d}). Timings/token counts above are for the latest call."
                        [count oldest (a/field latest sequence)]) (ak/return))]
      (history-text! text 1.0 0.8 0.25))))

(a/defn draw-language-history! :void
  "F2 opens bounded, scrollable exact driver text history in the game itself." []
  (when (ak/! (imgui/aguafria_imgui_is_visible))
    (ak/return))
  ;; Both histories remain available, but do not open stacked translucent
  ;; windows on first use. The user can expand the old team's monitor title.
  (ui/aguafria_ui_collapse_window_once "Aguafria racer cognition")
  (let [visible (ui/aguafria_ui_window_begin "Driver text history - F2" 850.0 560.0)]
    (ak/defer (ui/aguafria_ui_window_end))
    (when (ak/== visible 0) (ak/return))
    (history-text! "Experimental driver AI. Accepted means valid and installed, NOT a good decision. Team text is not connected here yet." 1.0 0.8 0.25)
    (let [^:var buffer (ak/as ak/undefined [:array 128 :u8])
          status (catch (std-mem/print (ak/& buffer)
                          "Current race: {d}. Retained replies below may belong to earlier races."
                          [simulation/race-epoch]) (ak/return))]
      (history-text! status 0.85 0.85 0.85))
    (when (ak/!= (ui/aguafria_ui_button "All racers") 0)
      (ak/= language-history-racer -1))
    (dotimes [identifier simulation/racer-count]
      (let [^:var buffer (ak/as ak/undefined [:array 12 :u8])
            label (catch (std-mem/printSentinel (ak/& buffer) "R{d}" [identifier] 0) (ak/return))]
        (when (ak/!= (mod identifier 10) 0)
          (ui/aguafria_ui_same_line))
        (when (ak/!= (ui/aguafria_ui_button (a/field label ptr)) 0)
          (ak/= language-history-racer (ak/intCast identifier)))))
    (ak/= :_ (ui/aguafria_ui_checkbox "Follow newest (uncheck to read older replies)" (ak/& language-history-follow)))
    (ak/= :_ (ui/aguafria_ui_checkbox "Show exact instructions too" (ak/& language-history-instructions)))
    (ak/= :_ (ui/aguafria_ui_checkbox "Show every call (including unchanged replies)" (ak/& language-history-every-call)))
    (when (>= language-history-racer 0)
      (let [identifier (ak/as (ak/intCast language-history-racer) :usize)
            enabled (a/field (a/index simulation/language-drivers identifier) enabled)
            ^:var buffer (ak/as ak/undefined [:array 96 :u8])
            status (catch (std-mem/print (ak/& buffer) "Showing R{d}. Plain-English driver: {s}."
                            [identifier (if enabled "enabled" "disabled")]) (ak/return))]
        (history-text! status 1.0 1.0 1.0)
        (when (ak/!= (ui/aguafria_ui_button (if enabled "Disable text driver" "Enable text driver")) 0)
          (ak/= :_ (simulation/request-language-mode! identifier (ak/! enabled))))))
    (when (ak/!= language-history-follow 0)
      (ak/= language-history-end (simulation/language-exchange-count)))
    ;; Keep selection/follow controls available while only the entries scroll.
    ;; EndChild is required even when BeginChild reports a clipped region.
    (let [entries-visible (ui/aguafria_ui_scroll_begin "driver-exchanges" language-history-follow)]
      (ak/defer (ui/aguafria_ui_scroll_end))
      (when (ak/== entries-visible 0) (ak/return))
      (let [end language-history-end
            ^:var pending (std-mem/zeroes (a/type simulation/LanguageExchange))
            ^:var count (ak/usize 0)
            ^:var oldest (ak/u64 0)]
        (dotimes [offset (ak/min end 128)]
          (let [entry (simulation/language-exchange-at (- end offset))]
            (when (and (a/field entry valid)
                       (or (< language-history-racer 0)
                           (ak/== (a/field (a/field (a/field entry result) request) actor)
                                  language-history-racer)))
              (if (and (> count 0) (ak/== language-history-every-call 0)
                       (same-language-exchange? pending entry))
                (ak/= count (+ count 1))
                (do
                  (when (> count 0) (draw-language-group! pending oldest count))
                  (ak/= pending entry)
                  (ak/= count 1)))
              (ak/= oldest (a/field entry sequence)))))
        (when (> count 0) (draw-language-group! pending oldest count))
        (when (ak/== count 0)
          (history-text! "No retained text exchanges for this selection. Select a racer and enable its text driver. First reply may take several seconds. Frozen entries eventually expire from the 128-entry history." 0.85 0.85 0.85))))))

(a/defvar frame-intervals [:array 120 :f64]
  (std-mem/zeroes (a/type [:array 120 :f64])))

(a/defvar frame-interval-index :usize 0)

(a/defvar frame-interval-count :usize 0)

(a/defvar frame-interval-sum :f64 0.0)

(a/defvar frame-previous-time :f64 0.0)

(a/defvar frame-report-enabled false)

(a/defvar frame-report-time :f64 0.0)

(a/defvar frame-report-tick :u64 0)

(a/defn measured-fps :f64
  "Actual render cadence across the last 120 intervals, including presentation
  waits. This is not the fixed physics rate or the target frame rate." []
  (if (> frame-interval-sum 0.0)
    (/ (ak/as (ak/floatFromInt frame-interval-count) :f64) frame-interval-sum)
    0.0))

(a/defn draw-frame-rate! :void
  "Always-visible measured FPS, in the game rather than the optional log view." []
  (let [now (glfw/glfwGetTime)]
    (when (> frame-previous-time 0.0)
      (let [interval (- now frame-previous-time)]
        (ak/= frame-interval-sum
              (+ (- frame-interval-sum (a/index frame-intervals frame-interval-index)) interval))
        (ak/= (a/index frame-intervals frame-interval-index) interval)
        (ak/= frame-interval-index (mod (+ frame-interval-index 1) 120))
        (ak/= frame-interval-count (ak/min 120 (+ frame-interval-count 1)))))
    (ak/= frame-previous-time now))
  ;; Optional console mirror allows measuring the exact standalone executable
  ;; without a REPL, debugger or a screenshot-derived FPS estimate.
  (when (and frame-report-enabled (>= (- frame-previous-time frame-report-time) 5.0))
    (let [tick (a/field (simulation/snapshot) tick)
          elapsed (- frame-previous-time frame-report-time)
          fps (measured-fps)]
      (std-debug/print
        "PERF {d:.2} FPS | {d:.2} ms/frame | {d:.2} physics Hz | {d} instance draws | {d} instances | {d} mesh-upload bytes\n"
        [fps (if (> fps 0.0) (/ 1000.0 fps) 0.0)
         (/ (ak/as (ak/floatFromInt (- tick (ak/min tick frame-report-tick))) :f64) elapsed)
         renderer/instance-draws renderer/instance-stream-used renderer/instance-upload-bytes])
      (ak/= frame-report-time frame-previous-time)
      (ak/= frame-report-tick tick)))
  (let [fps (measured-fps)
        ^:var buffer (ak/as ak/undefined [:array 128 :u8])
        label (catch (std-mem/printSentinel (ak/& buffer)
                       "{d:.1} FPS | {d:.2} ms/frame\nTarget 120 FPS | last {d} frames"
                       [fps (if (> fps 0.0) (/ 1000.0 fps) 0.0) frame-interval-count] 0)
                     (ak/return))
        visible (imgui/aguafria_imgui_panel_begin "##frame-rate" 290.0 0.5 0.16)]
    (ak/defer (imgui/aguafria_imgui_panel_end))
    (when visible
      (imgui/aguafria_imgui_label (a/field label ptr) 1.0 1.0 1.0))))

(a/defn draw-driving-telemetry! :void
  "Always-visible selected-driver telemetry. Reads the final pedal commands
  actually sent to physics, not an AI observation or desired speed as a proxy.
  Runs inside the existing ImGui frame through the generic drawing callback."
  {:attrs #{:export}} []
  (draw-frame-rate!)
  (ak/defer (draw-language-history!))
  (let [id (if render3d/follow-front-pack
             (a/field (simulation/snapshot) leader)
             render3d/camera-racer)
        racer (simulation/racer-view id)
        control (a/index simulation/vehicle-controls id)
        recovery (simulation/recovery-view id)
        turnaround (simulation/turnaround-view id)
        retired (a/field (simulation/retirement-view id) retired)
        overturned (driver/overturned? (simulation/vehicle-pose id 0))
        tint (render3d/racer-tint id)
        mode
               (ak/as (cond overturned "OVERTURNED / PROPULSION CUT"
                     retired "RETIRED"
                     (ak/!= simulation/race-state simulation/race-state-running) "RACE STOPPED"
                     (a/field racer finished) "FINISHED / 108 km/h COOLDOWN"
                     (a/field (a/field turnaround state) active) "TURNAROUND"
                     (ak/!= (a/field (a/field recovery state) phase) 0) "RECOVERY"
                     (> (a/field racer pit_state) simulation/pit-state-called) "PIT LANE / SERVICE"
                     :else "RACING") [:slice-const :u8])
        gear
               (ak/as (cond (< (a/field recovery gear) 0) "REVERSE"
                     (ak/== (a/field recovery gear) 0) "NEUTRAL"
                     :else "FORWARD") [:slice-const :u8])
        ^:var buffer (ak/as ak/undefined [:array 512 :u8])
        text (catch (std-mem/printSentinel (ak/& buffer)
                      "R{d}  LIVE DRIVING\n{d:.0} km/h\nThrottle {d:.0}%   Brake {d:.0}%\nSteering {d:.1} deg\nGear: {s}\n{s}\nAI cruise request: {d:.0} km/h\nCorner planner cap: {d:.0} km/h"
                      [id (* (a/field racer speed) 3600.0)
                       (* (a/field control throttle) 100.0)
                       (* (a/field control brake) 100.0)
                       (* (a/field control steering) 57.29578) gear mode
                       (* (a/field racer target_speed) 3600.0)
                       (* (driver/braking-envelope (* (a/field racer progress) 4309.0)
                                                   (* (a/field racer speed) 1000.0)) 3.6)] 0)
                    (ak/return))]
    (let [visible (imgui/aguafria_imgui_panel_begin "##driving-telemetry" 280.0 0.99 0.94)]
      (ak/defer (imgui/aguafria_imgui_panel_end))
      (when visible
        (imgui/aguafria_imgui_label (a/field text ptr)
          (a/field tint x) (a/field tint y) (a/field tint z))
        (imgui/aguafria_imgui_meter "THROTTLE" (a/field control throttle) 0.15 0.85 0.35)
        (imgui/aguafria_imgui_meter "BRAKE" (a/field control brake) 0.95 0.22 0.18)))))

(a/defn initialize! :bool
  "Attach ImGui to the existing GLFW/Vulkan objects without taking ownership."
  []
  (when initialized
    (ak/return true))
  (let [interop (renderer/renderer-interop)]
    (when (or (ak/! (a/field interop valid))
              (ak/! (abi-valid?)))
      (ak/return false))
    (when (ak/! (imgui/aguafria_imgui_initialize
                 (desktop/window-address)
                 (a/field interop instance)
                 (a/field interop physical_device)
                 (a/field interop device)
                 (a/field interop queue)
                 (a/field interop queue_family)
                 (a/field interop render_pass)
                 (a/field interop image_count)))
      (ak/return false))
    (renderer/set-overlay-renderer!
     (ak/& imgui/aguafria_imgui_render))
    (imgui/aguafria_imgui_set_draw_callback (ak/& draw-driving-telemetry!))
    (ak/= initialized true)
    (refresh!)
    true))

(a/defn set-visible! :void
  [[visible :bool]]
  (imgui/aguafria_imgui_set_visible visible))

(a/defn toggle-visible! :bool
  []
  (imgui/aguafria_imgui_toggle_visible))

(a/defn set-raw-protocol-visible! :void
  "Raw prompt bytes and token IDs remain opt-in."
  [[visible :bool]]
  (imgui/aguafria_imgui_set_raw_protocol visible))

(a/defn raw-protocol-visible? :bool
  "Whether the user explicitly enabled the technical protocol panel."
  []
  (imgui/aguafria_imgui_raw_protocol_visible))

(a/defn active? :bool
  "Whether ImGui currently borrows the live renderer."
  []
  initialized)

(a/defn visible? :bool
  "Whether the F2-toggleable ImGui window is currently visible."
  []
  (imgui/aguafria_imgui_is_visible))

(a/defn shutdown! :void
  "Detach the overlay before its borrowed Vulkan objects are destroyed."
  []
  (when initialized
    (imgui/aguafria_imgui_set_draw_callback ak/null)
    (renderer/set-overlay-renderer! ak/null)
    (renderer/renderer-wait-idle!)
    (imgui/aguafria_imgui_shutdown)
    (ak/= initialized false)))

(a/defn run! :bool
  "Native desktop loop with the human-readable cognition monitor."
  []
  (when (ak/! (desktop/initialize!))
    (ak/return false))
  (defer (desktop/shutdown!))
  (when (ak/! (initialize!))
    (ak/return false))
  (ak/= frame-report-enabled
    (let [value (std-c/getenv "RACING_FPS_LOG")]
      (if (ak/!= value ak/null)
        (std-mem/eql :u8 (std-mem/span (a/unwrap value)) "1")
        false)))
  (defer (shutdown!))
  (ak/while (desktop/should-run?)
    (refresh!)
    (ak/= :_ (desktop/frame!)))
  true)

(clojure.core/defn status
  "Clojure-friendly monitor state with all racer rows decoded."
  []
  (assoc (dissoc (a/value (monitor-snapshot)) :racers)
         :active (active?)
         :visible (visible?)
         :raw-protocol-visible (raw-protocol-visible?)
         :overlay-installed (renderer/overlay-installed?)
         :racers (mapv #(a/value (monitor-racer %)) (range (a/value simulation/racer-count)))
         :team-radios
         (mapv (fn [team-id]
                 (mapv #(a/value (monitor-radio-entry team-id %))
                       (range (monitor-radio-count team-id))))
               (range (a/value simulation/team-count)))
         :histories
         (mapv (fn [identifier]
                 (mapv #(a/value (monitor-history-entry identifier %))
                       (range (monitor-history-count identifier))))
               (range (a/value simulation/racer-count)))))
