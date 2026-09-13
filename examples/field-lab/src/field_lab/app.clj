(ns field-lab.app
  "Native application loop. All authoritative simulation state lives in Flecs."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [field-lab.soft-body :as soft]
            [field-lab.ball-fem :as fem]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.soft-mesh :as soft-mesh]
            [field-lab.surface :as surface]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem]
            [aguafria.std.fmt :as fmt]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.imgui-bindings]
            [aguafria-examples-native.imgui-controls]
            [aguafria-examples-native.bindings.imgui-controls :as ui]
            [aguafria-examples-native.bindings.imgui :as imgui]
            [aguafria-examples-native.mesh :as mesh]
            [aguafria-examples-native.renderer :as renderer]
            [aguafria-examples-native.readback :as readback]
            [field-lab.physics :as physics]
            [field-lab.scene :as scene]
            [field-lab.panel :as native-panel]
            [field-lab.panel-api :as panel]))

(az/defextern pitoco_aguafria_tick_v1
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :void
  [[panel [:c-pointer panel/LabPanel]]])

(az/defextern pitoco_aguafria_submit_v1
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32 [[command [:pointer {:size :c :const? true} panel/PitocoCommandV1]] [ticket [:c-pointer :u64]]])

(az/defextern pitoco_aguafria_shutdown_v1
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :void [])

(az/defvar host-service-state :u8 0)

(az/defn request-host-stop!
  "Retire an extension host on its owning thread before changing its native generation."
  :- :void []
  (ak/atomicStore :u8 (ak/& host-service-state) 1 :.release))

(az/defn host-stopped?
  :- :bool []
  (ak/== (ak/atomicLoad :u8 (ak/& host-service-state) :.acquire) 2))

(az/defn resume-host!
  :- :void []
  (ak/atomicStore :u8 (ak/& host-service-state) 0 :.release))

(az/defn service-host!
  :- :void [[state-panel [:* panel/LabPanel]]]
  (let [state (ak/atomicLoad :u8 (ak/& host-service-state) :.acquire)]
    (when (ak/== state 1)
      (pitoco_aguafria_shutdown_v1)
      (ak/atomicStore :u8 (ak/& host-service-state) 2 :.release))
    (when (ak/== state 0) (pitoco_aguafria_tick_v1 state-panel))))

(az/defconst aguafria-development-overlays true)

(az/defvar controls
  panel/LabPanel
  (panel/LabPanel
   {:radius 0.45
    :mass 0.62
    :height 3.5
    :gravity 9.81
    :restitution 0.78
    :friction 0.35
    :rolling 0.025
    :vx 1.1
    :vz 0.25
    :spin 1.5
    :yaw 0.55
    :pitch 0.30
    :distance 9.0
    :rate 1.0
    :paused 0
    :mode 1
    :deform 2
    :stiffness 10000.0
    :duration 6.0
    :baking 1
    :loop 1}))

(az/defvar bake-end :u32 1440)

(az/defstruct BakeRequest
  [[:config physics/Config] [:bodies :u32] [:model :u32] [:young :f64]
   [:duration :f64] [:action :u32] [:cursor :u32]])

(az/defvar pending-request BakeRequest (mem/zeroes (az/type BakeRequest)))

(az/defvar request-state :u8 0)

(az/defvar status-word :u64 0)

(az/defvar revision-word :u32 0)

;; The controller and UI exchange commands/status, never mutable solver storage.
;; Phases: 0 offline, 1 ready, 2 queued, 3 running, 4 publishing, 5 done,
;; 6 cancellation requested, 7 cancelled, 8 failed, 9 stale publication.
(az/defvar job-phase :u8 0)

(az/defvar job-command :u64 0)

(az/defvar job-progress-word :u64 0)

(az/defvar job-target-ticks :u32 60)

(az/defvar job-duration :f64 0.25)

(az/defvar job-ipc :u8 0)

(az/defvar job-panel-request :u8 0)

(az/defn request-job-panel!
  "Ask the UI thread to show or hide the authored job controls."
  :- :void [[visible :bool]]
  (ak/atomicStore :u8 (ak/& job-panel-request) (if visible 1 2) :.release))

(az/defn job-status
  :- :u8 []
  (ak/atomicLoad :u8 (ak/& job-phase) :.acquire))

(az/defn set-job-status!
  :- :void [[phase :u8]]
  (ak/atomicStore :u8 (ak/& job-phase) phase :.release))

(az/defn transition-job!
  :- :bool [[before :u8] [after :u8]]
  (ak/== (ak/cmpxchgStrong :u8 (ak/& job-phase) before after :.acq_rel :.acquire) null))

(az/defn take-job-command!
  :- :u64 []
  (ak/atomicRmw :u64 (ak/& job-command) :.Xchg 0 :.acq_rel))

(az/defn report-job-progress!
  :- :void [[tick :u32] [substeps :u32]]
  (ak/atomicStore :u64 (ak/& job-progress-word)
                  (ak/| (ak/<< (ak/as :u64 tick) 32) (ak/as :u64 substeps)) :.release))

(az/defn job-progress
  :- :u64 []
  (ak/atomicLoad :u64 (ak/& job-progress-word) :.acquire))

(az/defn queue-scene-job!
  :- :bool [[source :u32] [ticks :u32] [ipc :bool]]
  (let [phase (job-status)]
    (when (or (ak/== phase 0) (and (>= phase 2) (<= phase 4)) (ak/== phase 6)
              (< source 1) (> source 3) (< ticks 1) (> ticks 14400)) (ak/return false))
    (when (ak/! (transition-job! phase 2)) (ak/return false))
    (ak/atomicStore :u32 (ak/& job-target-ticks) ticks :.release)
    (report-job-progress! 0 0)
    (ak/atomicStore :u64 (ak/& job-command)
                    (ak/| (ak/<< (ak/as :u64 ticks) 32) (ak/as :u64 source)
                          (if ipc (ak/as :u64 8) (ak/as :u64 0))) :.release)
    true))

(az/defn cancel-scene-job!
  :- :void []
  (when (ak/! (transition-job! 2 6))
    (set! _ (transition-job! 3 6))))

(az/defn job-text!
  :- :void [[text [:slice-const :u8]]]
  (ui/aguafria_ui_wrapped_text (az/field text ptr) (az/field text len) 0.8 0.86 0.9))

(az/defn draw-job-panel!
  {:attrs #{:export}}
  :- :void []
  (let [phase (job-status)
        panel-request (ak/atomicRmw :u8 (ak/& job-panel-request) :.Xchg 0 :.acq_rel)]
    (when (ak/!= panel-request 0)
      (set! native-panel/authored-jobs-visible (ak/== panel-request 1)))
    (when (ak/! native-panel/authored-jobs-visible) (ak/return))
    (let [visible (ui/aguafria_ui_window_begin_at "Pitoco / authored bake jobs" 250.0 160.0 430.0 340.0)]
      (defer (ui/aguafria_ui_window_end))
      (when (ak/== visible 0) (ak/return))
      (when (ak/!= (ui/aguafria_ui_button "Close") 0)
        (set! native-panel/authored-jobs-visible false))
      (when (ak/== phase 0)
        (job-text! "No authored bake worker is attached.")
        (job-text! "The optional Clojure controller supplies these jobs; standalone playback works independently.")
        (ak/return))
      (job-text! "Offline FEM jobs / Clojure scene data")
      (if (or (ak/== phase 1) (>= phase 7) (ak/== phase 5))
        (do
          (set! _ (ui/aguafria_ui_slider_double "Duration" (ak/& job-duration)
                                                (/ 1.0 240.0) 6.0 "%.3f s"))
          (set! _ (ui/aguafria_ui_checkbox "IPC contact (experimental / slower)" (ak/& job-ipc)))
          (let [ticks (ak/as :u32 (ak/intFromFloat (ak/round (* 240.0 job-duration))))]
            (when (ak/!= (ui/aguafria_ui_button "Bake one ball") 0)
              (set! _ (queue-scene-job! 1 ticks (ak/!= job-ipc 0))))
            (ui/aguafria_ui_same_line)
            (when (ak/!= (ui/aguafria_ui_button "Bake three balls") 0)
              (set! _ (queue-scene-job! 2 ticks (ak/!= job-ipc 0))))
            (when (ak/!= (ui/aguafria_ui_button "Bake box + tetrahedron") 0)
              (set! _ (queue-scene-job! 3 ticks (ak/!= job-ipc 0)))))
          (job-text! "Complete caches appear in the viewport. Playback remains available."))
        (do
          (when (or (ak/== phase 2) (ak/== phase 3))
            (when (ak/!= (ui/aguafria_ui_button "Cancel bake") 0) (cancel-scene-job!)))
          (when (ak/== phase 4) (job-text! "Publishing complete cache..."))
          (when (ak/== phase 6) (job-text! "Cancellation requested; finishing the native batch..."))))
      (let [progress (job-progress)
            tick (ak/>> progress 32)
            target (ak/atomicLoad :u32 (ak/& job-target-ticks) :.acquire)
            ^{:var [:array 160 :u8]} buffer ak/undefined
            label (catch (fmt/bufPrintZ (ak/& buffer) "{d}/{d} ticks | {d} steps in frame"
                                         [tick target (ak/& progress 4294967295)]) (ak/return))]
        (ui/aguafria_ui_progress (ak/floatCast (ak/min 1.0 (/ (ak/as :f64 (ak/floatFromInt tick))
                                                               (ak/as :f64 (ak/floatFromInt target)))))
                                 (az/field label ptr)))
      (when (ak/== phase 5) (job-text! "Published. Use PLAY or the timeline below."))
      (when (ak/== phase 7) (job-text! "Cancelled. The previous cache is retained."))
      (when (ak/== phase 8) (job-text! "Bake failed. Details: build/authored-job-status.edn"))
      (when (ak/== phase 9) (job-text! "Scene changed during baking; the result was discarded.")))))

(az/defvar cache-request-state :u8 0)

(az/defvar pending-cache [:optional [:* cache/Cache]] null)

(az/defvar pending-group [:optional [:* group/Group]] null)

(az/defvar pending-cache-revision :u32 0)

(az/defvar cache-request-result :u8 0)

(az/defvar pending-scripted false)

(az/defvar pending-scene-parameters scene/ScriptedScene
  (mem/zeroes (az/type scene/ScriptedScene)))

(az/defn request-scripting!
  "Enable the optional external controller on the native owning thread."
  :- :u64
  [[directory [:pointer {:size :c :const? true} :u8]]]
  (let [command (panel/PitocoCommandV1
                 {:abi_version 1 :struct_size (ak/sizeOf panel/PitocoCommandV1)
                  :operation 9 :reserved 0 :integer 0 :text directory})
        ^{:var :u64} ticket 0]
    (if (ak/== (pitoco_aguafria_submit_v1 (ak/& command) (ak/& ticket)) 1) ticket 0)))

(az/defn live-revision
  :- :u32
  []
  (ak/atomicLoad :u32 (ak/& revision-word) :.acquire))

(az/defn request-cache!
  "A true return transfers ownership, including destruction if the job is stale."
  :- :bool
  [[owned [:* cache/Cache]] [revision :u32]]
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& cache-request-state) 0 1 :.acq_rel :.acquire) null)
    (ak/return false))
  (az/set-many!
    pending-cache owned
    pending-scripted false
    pending-group null
    pending-cache-revision revision)
  (ak/atomicStore :u8 (ak/& cache-request-result) 0 :.release)
  (ak/atomicStore :u8 (ak/& cache-request-state) 2 :.release)
  true)

(az/defn request-group!
  "Use the same ownership mailbox and revision check as single-body caches."
  :- :bool
  [[owned [:* group/Group]] [revision :u32]]
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& cache-request-state) 0 1 :.acq_rel :.acquire) null)
    (ak/return false))
  (az/set-many!
    pending-group owned
    pending-scripted false
    pending-cache null
    pending-cache-revision revision)
  (ak/atomicStore :u8 (ak/& cache-request-result) 0 :.release)
  (ak/atomicStore :u8 (ak/& cache-request-state) 2 :.release)
  true)

(az/defn request-scene!
  "Copy scene provenance into the ownership mailbox; the UI publishes both together."
  :- :bool
  [[owned [:* group/Group]] [revision :u32] [parameters scene/ScriptedScene]]
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& cache-request-state) 0 1 :.acq_rel :.acquire) null)
    (ak/return false))
  (az/set-many!
    pending-group owned pending-cache null pending-scripted true
    pending-scene-parameters parameters pending-cache-revision revision)
  (ak/atomicStore :u8 (ak/& cache-request-result) 0 :.release)
  (ak/atomicStore :u8 (ak/& cache-request-state) 2 :.release)
  true)

(az/defn cache-result
  :- :u8
  []
  (ak/atomicLoad :u8 (ak/& cache-request-result) :.acquire))

(az/defn consume-cache!
  :- :void
  []
  (when (ak/== (ak/atomicLoad :u8 (ak/& cache-request-state) :.acquire) 2)
    (let [owned (if (ak/!= pending-group null) (group/item (az/unwrap pending-group) 0)
                    (az/unwrap pending-cache))]
      (if (ak/== pending-cache-revision scene/revision)
        (do
          (if (ak/!= pending-group null) (scene/adopt-group! (az/unwrap pending-group))
              (scene/adopt-cache! owned))
          (when pending-scripted (scene/set-scripted-scene! pending-scene-parameters))
          (az/set-many!
            (az/field controls baking) 0
            (az/field controls paused) 1
            (az/field controls action) 0
            (az/field controls deform) 2
            (az/field controls mode) (ak/intCast scene/body-count)
            (az/field controls exported) 0
            (az/field controls radius) (az/field (az/field owned config) radius)
            (az/field controls mass) (az/field (az/field owned config) mass)
            (az/field controls height) (az/field (az/field owned config) height)
            (az/field controls gravity) (az/field (az/field owned config) gravity)
            (az/field controls friction) (az/field (az/field owned config) friction)
            (az/field controls restitution) (az/field (az/field owned config) restitution)
            (az/field controls rolling) (az/field (az/field owned config) rolling)
            (az/field controls vx) (az/field (az/field owned config) vx)
            (az/field controls vz) (az/field (az/field owned config) vz)
            (az/field controls spin) (az/field (az/field owned config) spin)
            (az/field controls duration) (az/field (cache/frame-info owned (- (az/field owned count) 1)) time)
            (az/field controls stiffness) (az/field owned young))
          (ak/atomicStore :u8 (ak/& cache-request-result) 1 :.release))
        (do
          (if (ak/!= pending-group null) (group/destroy! (az/unwrap pending-group))
              (cache/destroy! owned))
          (ak/atomicStore :u8 (ak/& cache-request-result) 2 :.release))))
    (az/set-many!
      pending-cache null
      pending-group null)
    (ak/atomicStore :u8 (ak/& cache-request-state) 0 :.release)))

(az/defn request-bake!
  "Queue a REPL-authored bake for the UI thread. Models: rigid=0, XPBD=1, FEM=2."
  :- :bool
  [[config physics/Config] [bodies :u32] [model :u32] [young :f64] [seconds :f64]]
  (when (or (and (ak/!= bodies 1) (ak/!= bodies 3)) (> model 2)
            (ak/! (and (> (az/field config radius) 0.0) (> (az/field config mass) 0.0)
                       (>= young 1000.0) (<= young 100000.0) (> seconds 0.0) (<= seconds 60.0))))
    (ak/return false))
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& request-state) 0 1 :.acq_rel :.acquire) null)
    (ak/return false))
  (set! pending-request (BakeRequest {:config config :bodies bodies :model model :young young
                                     :duration seconds :action 1 :cursor 0}))
  (ak/atomicStore :u8 (ak/& request-state) 2 :.release)
  true)

(az/defn request-view!
  "Queue seek/pause (3), export (4), or stop bake (8) on the native UI thread."
  :- :bool
  [[action :u32] [cursor :u32]]
  (when (ak/! (or (ak/== action 3) (ak/== action 4) (ak/== action 8)))
    (ak/return false))
  (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& request-state) 0 1 :.acq_rel :.acquire) null)
    (ak/return false))
  (az/set-many!
    (az/field pending-request action) action
    (az/field pending-request cursor) cursor)
  (ak/atomicStore :u8 (ak/& request-state) 2 :.release)
  true)

(az/defn live-status
  "Atomic packed telemetry: count bits 0–15, cursor 16–31, baking/FEM/failure 32–34."
  :- :u64
  []
  (ak/atomicLoad :u64 (ak/& status-word) :.acquire))

(az/defn consume-request!
  :- :void
  []
  (when (ak/== (ak/atomicLoad :u8 (ak/& request-state) :.acquire) 2)
    (when (ak/== (az/field pending-request action) 1)
      (let [config (az/field pending-request config)]
        (az/set-many!
          (az/field controls radius) (az/field config radius)
          (az/field controls mass) (az/field config mass)
          (az/field controls height) (az/field config height)
          (az/field controls gravity) (az/field config gravity)
          (az/field controls restitution) (az/field config restitution)
          (az/field controls friction) (az/field config friction)
          (az/field controls rolling) (az/field config rolling)
          (az/field controls vx) (az/field config vx)
          (az/field controls vz) (az/field config vz)
          (az/field controls spin) (az/field config spin)
          (az/field controls mode) (ak/intCast (az/field pending-request bodies))
          (az/field controls deform) (ak/intCast (az/field pending-request model))
          (az/field controls stiffness) (az/field pending-request young)
          (az/field controls duration) (az/field pending-request duration))))
    (az/set-many!
      (az/field controls action) (ak/intCast (az/field pending-request action))
      (az/field controls cursor) (ak/intCast (ak/min (az/field pending-request cursor) (- scene/count 1))))
    (when (ak/== (az/field pending-request action) 3)
      (set! (az/field controls paused) 1))
    (ak/atomicStore :u8 (ak/& request-state) 0 :.release)))

(az/defn edited-config
  :- physics/Config
  []
  (physics/Config {:radius (az/field controls radius)
                   :mass (az/field controls mass)
                   :height (az/field controls height)
                   :gravity (az/field controls gravity)
                   :restitution (az/field controls restitution)
                   :friction (az/field controls friction)
                   :rolling (az/field controls rolling)
                   :vx (az/field controls vx)
                   :vz (az/field controls vz)
                   :spin (az/field controls spin)}))

(az/defn material-energy
  :- :f64
  [[body soft/Body] [config physics/Config]]
  (if scene/continuum
    (fem/energy body config scene/stiffness)
    (soft/energy body config scene/stiffness)))

(az/defn export!
  :- :void
  []
  (let [config (scene/config)
        file (native-panel/export-begin! (az/field config radius)
                                     (az/field config mass)
                                     (az/field config height)
                                     (az/field config gravity)
                                     (az/field config restitution)
                                     (az/field config friction)
                                     (az/field config rolling)
                                     (az/field config vx)
                                     (az/field config vz)
                                     (az/field config spin)
                                     (ak/intCast scene/body-count)
                                     scene/dt
                                     (if scene/continuum 2 (if scene/deformable 1 0))
                                     scene/stiffness)]
    (when (ak/== file null) (set! (az/field controls exported) -1) (ak/return))
    (let [^{:var :bool} success true
          scripted (scene/scripted-scene)]
      (when (ak/!= scripted null)
        (when (ak/== 0 (native-panel/export-scene-source! file
                         (ak/& (az/index (az/field (az/unwrap scripted) source_hash) 0))
                         (ak/intCast scene/body-count) scene/dt))
          (set! success false))
        (dotimes [body scene/body-count]
          (let [item (az/unwrap (scene/mesh-cache-at body))
                observation (az/field (cache/frame-info item 0) observation)
                gravity (az/index (az/field (az/unwrap scripted) gravity) body)]
            (when (ak/== 0 (native-panel/export-scene-body! file (ak/intCast body)
                             (az/field observation mass) (az/field item young) (az/field item poisson)
                             (az/field gravity x) (az/field gravity y) (az/field gravity z)
                             (if (az/index (az/field (az/unwrap scripted) floor) body) 1 0)
                             (az/field (az/field item config) friction)))
              (set! success false)))))
      (dotimes [body scene/body-count]
        (let [owned (scene/mesh-cache-at body)]
          (when (ak/!= owned null)
            (dotimes [node (az/field (az/field (az/unwrap owned) reference) len)]
              (let [point (az/index (az/field (az/unwrap owned) reference) node)]
                (when (ak/== 0 (native-panel/export-reference! file (ak/intCast body) (ak/intCast node)
                                                               (az/field point x) (az/field point y) (az/field point z)))
                  (set! success false))))
            (dotimes [index (az/field (az/field (az/unwrap owned) cells) len)]
              (let [cell (az/index (az/field (az/unwrap owned) cells) index)]
                (when (ak/== 0 (native-panel/export-cell! file (ak/intCast body) (ak/intCast index)
                                                        (ak/intCast (az/index cell 0)) (ak/intCast (az/index cell 1))
                                                        (ak/intCast (az/index cell 2)) (ak/intCast (az/index cell 3))))
                  (set! success false)))))))
      (dotimes [i scene/count]
        (dotimes [body scene/body-count]
          (let [owned (scene/mesh-cache-at body)
                state (az/index (az/field (az/index scene/history i) bodies) body)
                position (az/field state position)
                velocity (az/field state velocity)
                omega (az/field state omega)
                q (az/field state orientation)]
            (when (ak/== 0
                         (native-panel/export-sample!
                          file
                          (ak/intCast body)
                          (az/field state time)
                          (az/field position x)
                          (az/field position y)
                          (az/field position z)
                          (az/field velocity x)
                          (az/field velocity y)
                          (az/field velocity z)
                          (az/field omega x)
                          (az/field omega y)
                          (az/field omega z)
                          (az/field q x)
                          (az/field q y)
                          (az/field q z)
                          (az/field q w)
                          (if (ak/!= owned null)
                            (let [observation (az/field (cache/frame-info (az/unwrap owned) (ak/intCast i)) observation)]
                              (+ (az/field observation elastic-energy) (az/field observation kinetic-energy)
                                 (az/field observation potential-energy)))
                            (if scene/deformable
                            (material-energy
                             (az/index (az/field (az/index scene/soft-history i) bodies) body)
                             config)
                            (physics/energy state config)))
                          (az/field state impulse)))
              (set! success false)))))
      (when scene/deformable
        (dotimes [i scene/count]
          (dotimes [body scene/body-count]
            (let [owned (scene/mesh-cache-at body)
                  sample (if (ak/!= owned null) (scene/soft-state 0)
                             (az/index (az/field (az/index scene/soft-history i) bodies) body))]
              (dotimes [particle (if (ak/!= owned null)
                                  (az/field (az/field (az/unwrap owned) reference) len) soft/particle-count)]
                (let [position (if (ak/!= owned null) (cache/position (az/unwrap owned) (ak/intCast i) particle)
                                   (az/index (az/field sample positions) particle))
                      velocity (if (ak/!= owned null) (cache/velocity (az/unwrap owned) (ak/intCast i) particle)
                                   (az/index (az/field sample velocities) particle))]
                  (when (ak/== 0
                               (native-panel/export-particle! file
                                                          (ak/intCast body)
                                                          (ak/intCast particle)
                                                          (* (ak/as :f64 (ak/floatFromInt i))
                                                             scene/dt)
                                                          (az/field position x)
                                                          (az/field position y)
                                                          (az/field position z)
                                                          (az/field velocity x)
                                                          (az/field velocity y)
                                                          (az/field velocity z)))
                    (set! success false))))))))
      (when (ak/== 0 (native-panel/export-end! file)) (set! success false))
      (set! (az/field controls exported) (if success 1 -1)))))

(az/defn draw-ui!
  {:attrs #{:export}}
  :- :void
  [[command :u64]]
  (let [state (scene/state)
        config (scene/config)]
    (az/set-many!
      (az/field controls time) (az/field state time)
      (az/field controls energy) (physics/energy state config)
      (az/field controls kinetic) (physics/kinetic state config)
      (az/field controls speed) (physics/length (az/field state velocity))
      (az/field controls impulse) (az/field state impulse)
      (az/field controls px) (az/field (az/field state position) x)
      (az/field controls py) (az/field (az/field state position) y)
      (az/field controls pz) (az/field (az/field state position) z)
      (az/field controls impacts) (ak/intCast (az/field state impacts))
      (az/field controls cursor) (ak/intCast scene/cursor)
      (az/field controls count) (ak/intCast scene/count)
      (az/field controls revision) (ak/intCast scene/revision))
    (az/set-many!
      (az/field controls energy) 0.0
      (az/field controls kinetic) 0.0
      (az/field controls impacts) 0)
    (dotimes [i scene/body-count]
      (let [body (scene/body-state (ak/intCast i))]
        (az/set-many!
          (az/field controls energy)
          (+ (az/field controls energy)
             (if scene/deformable
               (material-energy (scene/soft-state (ak/intCast i)) config)
               (physics/energy body config)))
          (az/field controls kinetic)
          (+ (az/field controls kinetic)
             (if scene/deformable
               (soft/kinetic (scene/soft-state (ak/intCast i))
                             config)
               (physics/kinetic body config)))
          (az/field controls impacts)
          (+ (az/field controls impacts)
             (ak/as :i32 (ak/intCast (az/field body impacts)))))))
    (when scene/deformable
      (let [body (scene/soft-state 0)
            radius (az/field config radius)]
        (az/set-many!
          (az/field controls clearance) (soft/clearance body)
          (az/field controls compression)
          (- 1.0
             (/ (soft/height body) (* 2.0 radius)))
          (az/field controls volume_ratio)
          (/ (soft/volume body) (* soft-mesh/unit-volume radius radius radius)))))
    (when scene/solver-failed
      (az/set-many!
        (az/field controls baking) 0
        (az/field controls paused) 1
        (az/field controls exported) -2))
    (let [owned (scene/mesh-cache)
          ^{:var :i32} nodes 0
          ^{:var :i32} tetrahedra 0]
      (when (ak/!= owned null)
        (az/set-many!
          (az/field controls energy) 0.0
          (az/field controls kinetic) 0.0
          (az/field controls speed) 0.0
          (az/field controls clearance) 1.0e30
          (az/field controls compression) -1.0e30
          (az/field controls volume_ratio) 0.0)
        (dotimes [body scene/body-count]
          (let [item (az/unwrap (scene/mesh-cache-at body))
                frame (cache/frame-info item scene/cursor)
                observation (az/field frame observation)]
            (az/set-many!
              nodes (+ nodes (ak/as :i32 (ak/intCast (az/field (az/field item reference) len))))
              tetrahedra (+ tetrahedra (ak/as :i32 (ak/intCast (az/field (az/field item cells) len))))
              (az/field controls energy)
              (+ (az/field controls energy) (az/field observation elastic-energy)
                 (az/field observation kinetic-energy) (az/field observation potential-energy))
              (az/field controls kinetic) (+ (az/field controls kinetic) (az/field observation kinetic-energy))
              (az/field controls speed)
              (ak/max (az/field controls speed) (/ (physics/length (az/field observation momentum)) (az/field observation mass)))
              (az/field controls clearance) (ak/min (az/field controls clearance) (az/field observation minimum-height))
              (az/field controls compression)
              (ak/max (az/field controls compression) (- 1.0 (/ (az/field frame height) (cache/reference-height item))))
              (az/field controls volume_ratio)
              (+ (az/field controls volume_ratio) (/ (az/field frame volume-ratio) (ak/as :f64 (ak/floatFromInt scene/body-count))))))))
      (native-panel/render! command (ak/& controls) nodes tetrahedra (ak/intCast (readback/status))
                                (ak/!= (scene/scripted-scene) null) (ak/& draw-job-panel!)))
    (when (ak/== (az/field controls action) 9)
      (set! _ (readback/acknowledge!))
      (set! _ (readback/request! "exports/frame.ppm"))
      (set! (az/field controls action) 0))
    (consume-request!)
    (service-host! (ak/& controls))
    (set! scene/requested-continuum (ak/== (az/field controls deform) 2))
    (ak/atomicStore :u64 (ak/& status-word)
                    (ak/| (ak/as :u64 scene/count)
                          (ak/<< (ak/as :u64 scene/cursor) 16)
                          (ak/<< (ak/as :u64 (if (ak/!= (az/field controls baking) 0) 1 0)) 32)
                          (ak/<< (ak/as :u64 (if scene/continuum 1 0)) 33)
                          (ak/<< (ak/as :u64 (if scene/solver-failed 1 0)) 34)
                          (ak/<< (ak/as :u64 (if (ak/!= (scene/mesh-cache) null) 1 0)) 35))
                    :.release)
    (ak/atomicStore :u32 (ak/& revision-word) scene/revision :.release)))

(az/defstruct FrameData
  "128-byte fragment push constant ABI; three spheres and a camera."
  {:layout :extern}
  [[:spheres [:array 3 [:array 4 :f32]]] [:rotations [:array 3 [:array 4 :f32]]]
   [:camera [:array 4 :f32]] [:viewport [:array 4 :f32]]])

(az/defn build-frame!
  {:attrs #{:export}}
  :- :u32
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (debug/assert (and (> width 0) (> height 0)))
  (consume-cache!)
  (renderer/set-frame-tag! scene/revision scene/cursor)
  (let [config (scene/config)
        ^:var frame (FrameData {:spheres [[0.0 0.0 0.0 0.0] [0.0 0.0 0.0 0.0] [0.0 0.0 0.0 0.0]]
                                :rotations [[0.0 0.0 0.0 1.0] [0.0 0.0 0.0 1.0]
                                            [0.0 0.0 0.0 1.0]]
                                :camera [(az/field controls yaw) (az/field controls pitch)
                                         (ak/floatCast (surface/camera-distance (ak/as :f64 (az/field controls distance)))) 0.0]
                                :viewport [1280.0 820.0 (ak/floatFromInt scene/body-count)
                                           (if scene/deformable 1.0 0.0)]})]
    (dotimes [i scene/body-count]
      (let [state (scene/body-state (ak/intCast i))
            center (az/field state position)
            q (az/field state orientation)]
        (az/set-many!
          (az/index (az/field frame spheres) i)
          (az/array-init [:array 4 :f32]
                         [(ak/floatCast (az/field center x))
                          (ak/floatCast (az/field center y))
                          (ak/floatCast (az/field center z))
                          (ak/floatCast (az/field config radius))])
          (az/index (az/field frame rotations) i)
          (az/array-init
           [:array 4 :f32]
           [(ak/floatCast (az/field q x)) (ak/floatCast (az/field q y))
            (ak/floatCast (az/field q z)) (ak/floatCast (az/field q w))]))))
    (renderer/push-frame-data! (ak/ptrCast (ak/& frame)) (ak/sizeOf FrameData)))
  (dotimes [i 3]
    (set! (az/index output i)
          (mesh/GpuVertex {:x (if (ak/== i 1) 3.0 -1.0)
                           :y (if (ak/== i 2) 3.0 -1.0)
                           :z 0.999999
                           :r 0.0
                           :g 0.0
                           :b 0.0
                           :nx 0.0
                           :ny 0.0
                           :nz 0.0
                           :wx 0.0
                           :wy 0.0
                           :wz 0.0
                           :roughness -1.0
                           :vx 0.0
                           :vy 0.0
                           :vz 0.0})))
  (if scene/deformable
    (+ 3
       (surface/emit! (ak/& (az/index output 3))
                      (ak/as :f64 (az/field controls yaw))
                      (ak/as :f64 (az/field controls pitch))
                      (surface/camera-distance (ak/as :f64 (az/field controls distance)))))
    3))

(az/defn main
  :- :void
  []
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (let [window (glfw/glfwCreateWindow 1280 820 "Pitoco - AguaFria" null null)]
    (debug/assert (ak/!= window null))
    (glfw/glfwFocusWindow window)
    (debug/assert (renderer/initialize-renderer! window))
    (set! scene/requested-continuum (ak/== (az/field controls deform) 2))
    (scene/initialize!)
    (scene/set-material! true (az/field controls stiffness))
    (let [interop (renderer/renderer-interop)]
      (debug/assert (imgui/aguafria_imgui_initialize (ak/intCast (ak/intFromPtr (az/unwrap
                                                                                 window)))
                                                     (az/field interop instance)
                                                     (az/field interop physical_device)
                                                     (az/field interop device)
                                                     (az/field interop queue)
                                                     (az/field interop queue_family)
                                                     (az/field interop render_pass)
                                                     (az/field interop image_count))))
    (renderer/set-overlay-renderer! (ak/& draw-ui!))
    (let [^{:var :f64} previous (glfw/glfwGetTime)
          ^{:var :f64} accumulator 0.0]
      (while (ak/== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE)
        (glfw/glfwPollEvents)
        (let [now (glfw/glfwGetTime)
              elapsed (ak/min 0.1 (ak/max 0.0 (- now previous)))]
          (set! previous now)
          (cond
            (or (ak/== (az/field controls action) 1)
                (ak/== (az/field controls action) 5)
                (ak/== (az/field controls action) 6))
            (do
              (scene/set-experiment! (ak/== (az/field controls mode) 3))
              (scene/set-material! (ak/!= (az/field controls deform) 0)
                                   (az/field controls stiffness))
              (scene/reset! (edited-config))
              (az/set-many!
                accumulator 0.0
                bake-end (ak/intFromFloat (/ (az/field controls duration) scene/dt))
                (az/field controls baking) 1
                (az/field controls paused) 1
                (az/field controls exported) 0))

            (ak/== (az/field controls action) 2)
            (scene/seek! (ak/min (+ scene/cursor 1) (- scene/count 1)))

            (ak/== (az/field controls action) 3)
            (do (scene/seek! (ak/intCast (az/field controls cursor)))
                (set! accumulator 0.0))

            (ak/== (az/field controls action) 4)
            (export!)

            (ak/== (az/field controls action) 8)
            (az/set-many!
              (az/field controls baking) 0
              (az/field controls paused) 1))
          (set! (az/field controls action) 0)
          (if (ak/!= (az/field controls baking) 0)
            (do
              (set! accumulator 0.0)
              (when (scene/bake-chunk! bake-end 16)
                (scene/seek! 0)
                (az/set-many!
                  (az/field controls baking) 0
                  (az/field controls paused) 0)))
            (if (ak/== (az/field controls paused) 0)
              (do
                (set! accumulator
                      (+ accumulator (* elapsed (ak/as :f64 (az/field controls rate)))))
                (while (>= accumulator scene/dt)
                  (if (< (+ scene/cursor 1) scene/count)
                    (scene/seek! (+ scene/cursor 1))
                    (if (ak/!= (az/field controls loop) 0)
                      (scene/seek! 0)
                      (set! (az/field controls paused) 1)))
                  (set! accumulator (- accumulator scene/dt))))
              (set! accumulator 0.0)))
          (set! _ (renderer/render! (ak/& build-frame!))))))
    (renderer/renderer-wait-idle!)
    (renderer/set-overlay-renderer! null)
    (imgui/aguafria_imgui_shutdown)
    (pitoco_aguafria_shutdown_v1)
    (scene/shutdown!)
    (renderer/shutdown-renderer!)
    (glfw/glfwDestroyWindow window)
    (glfw/glfwTerminate)))
