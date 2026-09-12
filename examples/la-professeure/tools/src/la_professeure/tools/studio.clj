(ns la-professeure.tools.studio
  "Native Vulkan recording workspace, attached to the game's existing render thread."
  (:require [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.std.unicode :as unicode]
            [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [la-professeure.scene :as scene]
            [la-professeure.gpu :as gpu]
            [aguafria-examples-native.mesh :as mesh]
            [la-professeure.miniaudio :as audio]
            [la-professeure.tools.recorder :as recorder]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.tools.takes :as files]
            [la-professeure.core :as core]
            [la-professeure.dialogue :as dialogue]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files StandardCopyOption CopyOption]))

(defn- state-assignment-forms [setter bindings]
  (when-not (and (vector? bindings)
                 (seq bindings)
                 (even? (count bindings)))
    (throw (IllegalArgumentException.
            "set-state! requires a nonempty vector of field/value pairs")))
  (when-not (every? symbol? (take-nth 2 bindings))
    (throw (IllegalArgumentException.
            "set-state! targets must be native state symbols")))
  (cons 'do
        (map (fn [[field value]]
               (list setter field value))
             (partition 2 bindings))))

(defmacro ^:private set-state!
  "Group related Zig state assignments in source order. Later values see earlier
  assignments. Not atomic: cross-thread state keeps its explicit atomics."
  [bindings]
  (state-assignment-forms 'set! bindings))

(defmacro ^:private set-native-state!
  "JVM counterpart of set-state!: ordered writes to native Vars. Use inside a
  render! callback; this macro does not schedule work or provide atomicity."
  [bindings]
  (state-assignment-forms 'aguafria.zig/set-value! bindings))

(az/defvar selected :u32 1)
(az/defvar studio-window [:optional [:* glfw/GLFWwindow]] ak/null)
(az/defvar attached :bool false)
(az/defvar observed-shaders :u64 0)
(az/defvar renderer gpu/RendererContext (mem/zeroes (az/type gpu/RendererContext)))
(az/defvar voices [:array 2 audio/ma_sound] (mem/zeroes (az/type [:array 2 audio/ma_sound])))
(az/defvar voice-decoders [:array 2 audio/ma_decoder] (mem/zeroes (az/type [:array 2 audio/ma_decoder])))
(az/defvar voice-slot :usize 0)
(az/defvar voice-ready :bool false)
(az/defvar playback-engine audio/ma_engine (mem/zeroes (az/type audio/ma_engine)))
(az/defvar playback-ready :bool false)
(az/defvar playback-interrupted :bool false)
(az/defvar playback-output :u32 4294967295)
(az/defvar headphones :u32 4294967295)
(az/defvar playback-level :u32 0)
(az/defvar playback-peak :u32 0)
(az/defvar playback-signal-frames :u64 0)
(az/defvar audition-boost :bool false)
(az/defvar audition-source-peak :f32 1.0)
(az/defvar audition-gain :f32 1.0)

(az/defn update-audition-gain! :- :void []
  (set! audition-gain (if (and audition-boost (> audition-source-peak 0.000001))
                        (ak/max 1.0 (ak/min 1000.0 (/ 0.2 audition-source-peak)))
                        1.0))
  (when voice-ready
    (audio/ma_sound_set_volume (ak/& (az/index voices voice-slot)) (* 0.8 audition-gain))))

(az/defn playback-peak-value :- :u32 []
  (ak/atomicLoad :u32 (ak/& playback-peak) :.acquire))

(az/defn playback-signal-count :- :u64 []
  (ak/atomicLoad :u64 (ak/& playback-signal-frames) :.acquire))

(az/defn playback-process! {:zig/qualifiers "callconv(.c)"} :- :void
  [[user [:optional [:* :anyopaque]]] [output [:c-pointer :f32]] [frames :u64]]
  (set! _ user)
  (let [^{:var :f32} peak 0.0]
    (when (ak/!= output ak/null)
      (dotimes [i (* frames 2)]
        (set! peak (ak/max peak (ak/abs (az/index output i))))))
    (let [level (ak/as :u32 (ak/intFromFloat (* 1000000.0 (ak/min 1.0 peak))))]
      (ak/atomicStore :u32 (ak/& playback-level) level :.release)
      (when (> level (ak/atomicLoad :u32 (ak/& playback-peak) :.acquire))
        (ak/atomicStore :u32 (ak/& playback-peak) level :.release))
      (when (> level 0)
        (set! _ (ak/atomicRmw :u64 (ak/& playback-signal-frames) :.Add frames :.monotonic))))))

(az/defvar frame-cursor :f32 0.0)
(az/defvar framebuffer-scale :f32 1.0)
(az/defvar route-menu :u32 0)
(az/defvar route-offset :u32 0)
(az/defvar route-focus :u32 0)

(az/defn route-total :- :u32 []
  (if (or (ak/== route-menu 1) (ak/== route-menu 3))
    recorder/capture-count
    recorder/playback-count))

(az/defn route-selected :- :u32 []
  (cond
    (ak/== route-menu 1)
    microphone
    (ak/== route-menu 2)
    effects-output
    (ak/== route-menu 3)
    return-input
    :else
    headphones))

(az/defn route-open! :- :void [[menu :u32]]
  (when (or (busy?)
            (recorder/input-check-active?)
            (< menu 1)
            (> menu 4))
    (ak/return))
  (set-state! [route-menu menu
               route-focus (ak/min (route-selected) (- (ak/max 1 (route-total)) 1))
               route-offset (* (/ route-focus 8) 8)
               name-focus false
               name-drag false
               trim-drag 0
               clicked false
               route-click false]))

(az/defn route-move! :- :void [[down? :bool]]
  (if down?
    (set! route-focus (ak/min (+ route-focus 1) (- (ak/max 1 (route-total)) 1)))
    (set! route-focus (- route-focus (ak/min route-focus 1))))
  (set! route-offset (* (/ route-focus 8) 8)))

(az/defn route-select! :- :void [[index :u32]]
  (when (or (busy?)
            (recorder/input-check-active?)
            (ak/== route-menu 0)
            (>= index (route-total)))
    (ak/return))
  (cond
    (ak/== route-menu 1)
    (set! microphone index)
    (ak/== route-menu 2)
    (set! effects-output index)
    (ak/== route-menu 3)
    (set! return-input index)
    (ak/== route-menu 4)
    (when (ak/!= headphones index)
      (close-playback!)
      (mixer/close!)
      (set-state! [preview-paused false
                   headphones index])))
  (set! route-menu 0))

(az/defvar route-click :bool false)

(az/defn game-audio-suppressed? :- :bool []
  scene/studio-audio-suppressed)

;; Only game's sounds are gated; the studio owns its selected playback device.
;; Preserve the user's M-key mute independently of temporary studio focus.
(az/defn suppress-game-audio! :- :void [[suppressed :bool]]
  (when (ak/== scene/studio-audio-suppressed suppressed)
    (ak/return))
  (set! scene/studio-audio-suppressed suppressed)
  (when scene/voice-ready
    (audio/ma_sound_set_volume (ak/& (az/index scene/voices scene/voice-slot))
                               (if (or scene/audio-muted suppressed)
                                 0.0
                                 0.8)))
  (when scene/audio-ready
    (audio/ma_sound_set_volume (ak/& (az/index scene/tracks scene/active-track))
                               (if (or scene/audio-muted suppressed)
                                 0.0
                                 0.35))))

(az/defn stop-voice! :- :void []
  (when voice-ready
    (audio/ma_sound_uninit (ak/& (az/index voices voice-slot)))
    (set! _ (audio/ma_decoder_uninit (ak/& (az/index voice-decoders voice-slot))))
    (set! voice-ready false)))

(az/defn close-playback! :- :void []
  (stop-voice!)
  (when playback-ready
    (audio/ma_engine_uninit (ak/& playback-engine))
    (set-state! [playback-ready false
                 playback-interrupted false
                 playback-output 4294967295])))

(az/defn initialize-listen-output! :- :void []
  ;; Never silently send first-run audition into the effects loopback.
  ;; An explicit user selection is preserved, including a virtual output.

  (when (ak/!= headphones 4294967295)
    (ak/return))
  (dotimes [i recorder/playback-count]
    (let [name (recorder/device-name false (ak/intCast i))]
      (when (and (ak/== (mem/indexOf (az/type :u8) name "BlackHole") ak/null)
                 (ak/== (mem/indexOf (az/type :u8) name "Aggregate") ak/null))
        (when (or (ak/== headphones 4294967295)
                  (ak/!= (az/field (az/index recorder/playback-info i) isDefault) 0))
          (set! headphones (ak/intCast i)))))))

(az/defn prepare-playback! :- :bool []
  (initialize-listen-output!)
  (when (or (ak/! recorder/initialized)
            (>= headphones recorder/playback-count))
    (ak/return false))
  (when (and playback-ready (ak/== playback-output headphones))
    (when playback-interrupted
      (when (ak/!= ((az/field recorder/api ma_engine_start) (ak/ptrCast (ak/& playback-engine))) 0)
        (ak/return false))
      (set! playback-interrupted false))
    (ak/return true))
  (close-playback!)
  (let [^:var config ((az/field recorder/api ma_engine_config_init))]
    (set! (az/field config pContext) (ak/& recorder/context))
    (set! (az/field config pPlaybackDeviceID) (ak/& (az/field (az/index recorder/playback-info headphones) id)))
    (set! (az/field config channels) 2)
    (set! (az/field config sampleRate) 48000)
    (set! (az/field config onProcess) (ak/& playback-process!))
    (when (ak/!= ((az/field recorder/api ma_engine_init) (ak/& config) (ak/ptrCast (ak/& playback-engine))) 0)
      (ak/return false)))
  (set-state! [playback-ready true
               playback-output headphones])
  true)

(az/defn play-voice-file! :- :bool [[path [:slice-const :u8]]]
  (when (or (ak/! (prepare-playback!))
            (ak/== (az/field path len) 0)
            (>= (az/field path len) 4096))
    (ak/return false))
  (let [^:var filename (mem/zeroes (az/type [:array 4096 :u8]))
        next (mod (+ voice-slot 1) 2)
        decoder (ak/& (az/index voice-decoders next))
        candidate (ak/& (az/index voices next))]
    (dotimes [i (az/field path len)]
      (when (ak/== (az/index path i) 0)
        (ak/return false)))
    (ak/memcpy (az/slice filename 0 (az/field path len)) path)
    (when (ak/!= (audio/ma_decoder_init_file (ak/& filename) ak/null decoder) audio/MA_SUCCESS)
      (ak/return false))
    (when (ak/!= (audio/ma_sound_init_from_data_source (ak/& playback-engine)
                                                       (ak/as (az/type [:* audio/ma_data_source]) (ak/ptrCast decoder))
                                                       0 ak/null candidate) audio/MA_SUCCESS)
      (set! _ (audio/ma_decoder_uninit decoder))
      (ak/return false))
    (update-audition-gain!)
    (audio/ma_sound_set_looping candidate 0)
    (audio/ma_sound_set_volume candidate (* 0.8 audition-gain))
    (ak/atomicStore :u32 (ak/& playback-peak) 0 :.release)
    (ak/atomicStore :u64 (ak/& playback-signal-frames) 0 :.release)
    (when (ak/!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate)
      (set! _ (audio/ma_decoder_uninit decoder))
      (ak/return false))
    (stop-voice!)
    (set-state! [voice-slot next
                 voice-ready true])
    true))

(az/defvar scroll :f32 0.0)
(az/defvar focus-scroll :f32 0.0)
(az/defvar content-height :f32 0.0)
(az/defvar focus-height :f32 0.0)
(az/defvar mouse-x :f64 0.0)
(az/defvar mouse-y :f64 0.0)
(az/defvar mouse-down false)
(az/defvar monitor-level-drag :bool false)
(az/defvar clicked false)
(az/defvar pending :u32 0)
(az/defvar busy :u8 0)
(az/defvar capture-phase :u8 0)

(az/defn capture-phase-value :- :u8 []
  (if (and (ak/== capture-phase 2) (recorder/tail-active?))
    3
    capture-phase))

(az/defvar record-enabled :u8 0)
(az/defvar record-track :u32 4294967295)
(az/defvar capture-id-text [:array 64 :u8] (mem/zeroes (az/type [:array 64 :u8])))
(az/defvar capture-id-length :usize 0)
;; A passage can use the whole story text capacity. Never truncate a recording
;; script or keep a pointer into the story's hot-swapped backing storage.
(az/defvar capture-script-text [:array 262144 :u8] (mem/zeroes (az/type [:array 262144 :u8])))
(az/defvar capture-script-length :usize 0)

(az/defn capture-script! :- :bool
  [[id [:slice-const :u8]] [text [:slice-const :u8]]]
  (when (or (ak/== (az/field id len) 0)
            (> (az/field id len) 64)
            (> (az/field text len) 262144))
    (ak/return false))
  (ak/memcpy (az/slice capture-id-text 0 (az/field id len)) id)
  (ak/memcpy (az/slice capture-script-text 0 (az/field text len)) text)
  (set-state! [capture-id-length (az/field id len)
               capture-script-length (az/field text len)])
  true)

(az/defn captured-id :- [:slice-const :u8] []
  (az/slice capture-id-text 0 capture-id-length))

(az/defn capture-passage? :- :bool [[index :u32]]
  (and (> capture-phase 0)
       (> capture-id-length 0)
       (< index scene/passage-entity-count)
       (mem/eql (az/type :u8) (node-id index) (captured-id))))

(az/defn showing-capture-script? :- :bool []
  (and (ak/== workspace-mode 1)
       (> capture-phase 0)
       (> capture-id-length 0)))

(az/defn waveform-passage-id :- [:slice-const :u8] []
  (if (showing-capture-script?)
    (captured-id)
    (node-id selected)))

(az/defvar record-request-text [:array 65 :u8] (mem/zeroes (az/type [:array 65 :u8])))
(az/defvar record-request-length :usize 0)
(az/defvar record-mode :u8 1)

(az/defn capture-fx? :- :bool []
  ;; The configured next recording is not the active operation. An offline
  ;; effects pass can process a saved take while the next-recording mode is Dry.
  (and (>= capture-phase 2)
       (>= recorder/mode 2)))

(az/defn effects-pass? :- :bool []
  (and (>= capture-phase 2)
       (ak/== recorder/mode 2)))

(az/defn record-route-label :- [:slice-const :u8] []
  (cond
    (effects-pass?) "FX pass"
    (capture-fx?) "Live FX"
    (>= capture-phase 2) "Dry"
    (ak/== record-mode 8) "Live FX"
    :else "Dry"))

(az/defvar countdown-until :f64 0.0)
(az/defvar capture-cursor :f32 0.0)
(az/defvar status-text [:array 256 :u8] (mem/zeroes (az/type [:array 256 :u8])))
(az/defvar status-length :usize 0)
(az/defvar alert-text [:array 256 :u8] (mem/zeroes (az/type [:array 256 :u8])))
(az/defvar alert-length :usize 0)
(az/defvar input-meter-text [:array 64 :u8] (mem/zeroes (az/type [:array 64 :u8])))
(az/defvar input-meter-length :usize 0)
(az/defvar return-meter-text [:array 64 :u8] (mem/zeroes (az/type [:array 64 :u8])))
(az/defvar return-meter-length :usize 0)
(az/defvar microphone :u32 0)
(az/defvar return-input :u32 0)
(az/defvar effects-output :u32 0)
(az/defvar test-click false)
(az/defvar test-x :f64 0.0)
(az/defvar test-y :f64 0.0)
(az/defvar page :u32 0)
(az/defvar workspace-mode :u32 0)
(az/defvar workspace-record-prior :u8 0)
(az/defstruct TakeCard
  [[available :bool]
   [missing :bool]
   [chosen :bool]
   [seconds :f32]
   [label-length :usize]
   [label [:array 128 :u8]]
   [bins [:array 32 :f32]]])
(az/defvar take-cards [:array 48 TakeCard] (mem/zeroes (az/type [:array 48 TakeCard])))
(az/defvar take-grid-offset :u32 4294967295)
(az/defvar take-grid-rows :u32 0)
(az/defvar take-grid-revision :u64 0)
(az/defvar take-grid-action-slot :u32 0)
(az/defvar take-grid-action-revision :u64 0)
(az/defvar record-scroll :f32 0.0)
(az/defvar record-text-height :f32 0.0)
(az/defvar tail-seconds :u32 1)
(az/defvar countdown-seconds :u32 0)

(az/defn begin-countdown-clock! :- :void []
  (set! countdown-until (+ (glfw/glfwGetTime) (ak/as :f64 (ak/floatFromInt countdown-seconds)))))

(az/defvar monitor-enabled :u8 0)
(az/defvar compensate :u8 1)
(az/defvar trim-in :u32 0)
(az/defvar trim-out :u32 100)
(az/defvar name-focus false)

(az/defn name-focused?
  "Typed host boundary: native state storage is not a Clojure truth value."
  :- :bool []
  name-focus)
(az/defvar back-down false)
(az/defvar edit-name [:array 128 :u8] (mem/zeroes (az/type [:array 128 :u8])))
(az/defvar name-length :usize 0)
(az/defvar name-caret :usize 0)
(az/defvar name-anchor :usize 0)
(az/defvar name-view :usize 0)
(az/defvar name-drag false)
(az/defvar name-drag-elapsed :f64 0.0)
(az/defvar name-pointer-time :f64 0.0)

(az/defstruct NameDraft {:layout :extern}
              [[text [:array 128 :u8]] [length :usize] [caret :usize] [anchor :usize] [view :usize]])

(az/defvar name-history [:array 33 NameDraft] (mem/zeroes (az/type [:array 33 NameDraft])))
(az/defvar name-history-position :usize 0)
(az/defvar name-history-end :usize 0)
(az/defvar name-batch :bool false)
(az/defvar name-batch-recorded :bool false)

(az/defn name-draft :- NameDraft []
  (NameDraft {:text edit-name :length name-length :caret name-caret :anchor name-anchor :view name-view}))

(az/defn name-restore! :- :void [[draft NameDraft]]
  (set-state! [edit-name (az/field draft text)
               name-length (az/field draft length)
               name-caret (az/field draft caret)
               name-anchor (az/field draft anchor)
               name-view (az/field draft view)]))

(az/defn name-checkpoint! :- :void []
  ;; A paste is one edit; draft history never enters the project undo journal.

  (when (and name-batch name-batch-recorded)
    (ak/return))
  (when name-batch
    (set! name-batch-recorded true))
  (when (ak/== name-history-position 32)
    (dotimes [i 31]
      (set! (az/index name-history i) (az/index name-history (+ i 1))))
    (set! name-history-position 31))
  (set! (az/index name-history name-history-position) (name-draft))
  (set-state! [name-history-position (+ name-history-position 1)
               name-history-end name-history-position]))

(az/defn name-undo! :- :void [[redo? :bool]]
  (when (or (busy?)
            (if redo?
              (>= name-history-position name-history-end)
              (ak/== name-history-position 0)))
    (ak/return))
  (set! (az/index name-history name-history-position) (name-draft))
  (set! name-history-position (if redo?
                                (+ name-history-position 1)
                                (- name-history-position 1)))
  (name-restore! (az/index name-history name-history-position)))

(az/defn name-previous :- :usize [[position :usize]]
  (let [^{:var :usize} p (ak/min position name-length)]
    (when (> p 0)
      (set! p (- p 1)))
    (ak/while (and (> p 0) (ak/== (& (az/index edit-name p) 192) 128))
      (set! p (- p 1)))
    p))

(az/defn name-next :- :usize [[position :usize]]
  (let [^{:var :usize} p (ak/min (+ position 1) name-length)]
    (ak/while (and (< p name-length)
                   (ak/== (& (az/index edit-name p) 192) 128))
      (set! p (+ p 1)))
    p))

(az/defn name-move! :- :void [[position :usize] [extend? :bool]]
  (set! name-caret (ak/min position name-length))
  (when (ak/! extend?)
    (set! name-anchor name-caret)))

(az/defn name-delete! :- :void []
  (let [a (ak/min name-caret name-anchor)
        b (ak/max name-caret name-anchor)]
    (dotimes [i (- name-length b)]
      (set! (az/index edit-name (+ a i)) (az/index edit-name (+ b i))))
    (set-state! [name-length (- name-length (- b a))
                 name-caret a
                 name-anchor a
                 name-view (ak/min name-view a)])))

(az/defvar details [:array 512 :u8] (mem/zeroes (az/type [:array 512 :u8])))
(az/defvar details-length :usize 0)
(az/defvar wave [:array 128 :f32] (mem/zeroes (az/type [:array 128 :f32])))
(az/defvar waveform-owner [:array 64 :u8] (mem/zeroes (az/type [:array 64 :u8])))
(az/defvar waveform-owner-length :usize 0)
(az/defvar waveform-uploaded :bool false)
(az/defvar track-offset :u32 0)
(az/defvar track-snapshot :u32 4294967295)

(az/defstruct ClipViewportRow {:layout :extern}
              [[seconds :f32] [start :f32] [count :u32] [wave [:array 128 :f32]]])

(az/defvar clip-viewport [:array 32 ClipViewportRow]
  (mem/zeroes (az/type [:array 32 ClipViewportRow])))
(az/defvar track-snapshot-count :u32 0)
(az/defvar clip-upload-revision :u64 0)
(az/defvar take-seconds :f32 0.0)

(az/defconst comparison-unmarked :u8 0)
(az/defconst comparison-select-b :u8 1)
(az/defconst comparison-listen-a :u8 2)
(az/defconst comparison-listen-b :u8 3)
(az/defconst comparison-missing-a :u8 4)
(az/defconst comparison-missing-b :u8 5)

(az/defvar comparison-owner [:array 64 :u8] (mem/zeroes (az/type [:array 64 :u8])))
(az/defvar comparison-owner-length :usize 0)
(az/defvar comparison-code :u8 comparison-unmarked)

(az/defn comparison-view! :- :void [[id [:slice-const :u8]] [code :u8]]
  (set! comparison-owner-length (ak/min 64 (az/field id len)))
  (ak/memcpy (az/slice comparison-owner 0 comparison-owner-length)
             (az/slice id 0 comparison-owner-length))
  (set! comparison-code code))

(az/defn selected-comparison-code :- :u8 []
  (if (mem/eql (az/type :u8)
               (selected-id)
               (az/slice comparison-owner 0 comparison-owner-length))
    comparison-code
    comparison-unmarked))

(az/defn comparison-enabled? :- :bool []
  (let [code (selected-comparison-code)]
    (and (take-editable?)
         (or (ak/== code comparison-listen-a)
             (ak/== code comparison-listen-b)))))

(az/defn comparison-label :- [:slice-const :u8] []
  (let [code (selected-comparison-code)]
    (cond
      (ak/== code comparison-select-b) "A marked: select B"
      (ak/== code comparison-listen-a) "A/B: listen A"
      (ak/== code comparison-listen-b) "A/B: listen B"
      (ak/== code comparison-missing-a) "A unavailable"
      (ak/== code comparison-missing-b) "B unavailable"
      :else "Compare A/B")))

(az/defn comparison-hint :- [:slice-const :u8] []
  (let [code (selected-comparison-code)]
    (cond
      (ak/! (take-editable?)) "Select a loaded take before comparing."
      (ak/== code comparison-select-b) "A is marked. Select a different take for this passage as B."
      (ak/== code comparison-missing-a) "Take A is missing. Select an available take and mark A again."
      (ak/== code comparison-missing-b) "Take B is missing. Select an available take."
      :else "Mark take A for this passage, then select a different take as B.")))

(az/defvar timeline-seconds :f32 30.0)
(az/defvar timeline-start :f32 0.0)
(az/defvar seek-seconds :f32 0.0)
(az/defvar preview-node :u32 4294967295)
(az/defvar trim-drag :u32 0)
(az/defvar preview-paused :bool false)
(az/defvar mix-mode :bool false)
(az/defvar loop-from :f32 0.0)
(az/defvar loop-to :f32 0.0)
(az/defvar follow-playhead :bool true)
(az/defvar track-scroll :f32 0.0)
(az/defvar bar-grab :f32 0.0)
(az/defvar event-pressed false)
(az/defvar event-double false)
(az/defvar double-clicked false)
(az/defvar press-x :f64 0.0)
(az/defvar press-y :f64 0.0)
(az/defvar last-press-time :f64 -10.0)
(az/defvar last-press-x :f64 -100.0)
(az/defvar last-press-y :f64 -100.0)
(az/defvar callbacks-installed false)
(az/defvar previous-scroll glfw/GLFWscrollfun ak/null)
(az/defvar previous-key glfw/GLFWkeyfun ak/null)
(az/defvar previous-mouse glfw/GLFWmousebuttonfun ak/null)
(az/defvar previous-char glfw/GLFWcharfun ak/null)
(az/defvar hand-cursor [:optional [:* glfw/GLFWcursor]] ak/null)
(az/defvar resize-cursor [:optional [:* glfw/GLFWcursor]] ak/null)
(az/defvar vertical-cursor [:optional [:* glfw/GLFWcursor]] ak/null)
(az/defvar text-cursor [:optional [:* glfw/GLFWcursor]] ak/null)
(az/defvar hint-text [:array 256 :u8] (mem/zeroes (az/type [:array 256 :u8])))
(az/defvar hint-length :usize 0)
(az/defvar rendered-frames :u64 0)

;; One logical-point layout for drawing AND hit testing. The left track headers
;; and right routing column keep their widths; extra space belongs to the editor.
(az/defvar window-width :f32 1100.0)
(az/defvar window-height :f32 760.0)
(az/defvar routing-visible :bool true)
(az/defvar editor-top :f32 444.0)
(az/defvar divider-drag :bool false)
(az/defvar divider-grab :f32 0.0)

(az/defn right-x :- :f32 [[base :f32]]
  (+ base (- window-width 1100.0)))

(az/defn routing-space :- :f32 []
  (if routing-visible
    0.0
    202.0))

(az/defn content-x :- :f32 [[base :f32]]
  (+ (right-x base) (routing-space)))

(az/defn bottom-y :- :f32 [[base :f32]]
  (+ base (- window-height 760.0)))

(az/defn timeline-width :- :f32 []
  (+ (- window-width 458.0) (routing-space)))

(az/defn timeline-center :- :f32 []
  (+ 242.0 (/ (timeline-width) 2.0)))

(az/defn main-width :- :f32 []
  (+ (- window-width 232.0) (routing-space)))

(az/defn show-routing! :- :void [[visible :bool]]
  ;; Visibility is not device/monitor state. Dismiss the popup and any old drag.

  (set-state! [routing-visible visible
               route-menu 0
               route-click false
               trim-drag 0
               divider-drag false
               monitor-level-drag false
               clicked false]))

(az/defn editor-y :- :f32 [[base :f32]]
  (+ base (- editor-top 444.0)))

(az/defn track-height :- :f32 []
  (- editor-top 182.0))

(az/defn visible-row-count :- :u32 []
  (if (ak/== workspace-mode 2)
    (ak/intFromFloat (ak/max 2.0 (ak/min 16.0 (/ (- editor-top 176.0) 62.0))))
    (ak/intFromFloat (ak/max 2.0 (ak/min 32.0 (/ (+ (track-height) 2.0) 56.0))))))

(az/defn set-editor-top! :- :void [[top :f32]]
  (set-state! [editor-top (ak/max 316.0 (ak/min (ak/min 1972.0 (- window-height 300.0)) top))
               track-offset (ak/min track-offset (- (ak/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))]))

(az/defn editor-text-height :- :f32 []
  (- window-height editor-top 220.0))

(az/defn record-pane-height :- :f32 []
  (- window-height 535.0))

(az/defn record-row-count :- :u32 []
  (ak/intFromFloat (ak/max 3.0 (ak/min 32.0 (/ (- window-height 292.0) 54.0)))))

(az/defn select-workspace! :- :void [[mode :u32]]
  ;; Record mode temporarily enables REC, not capture. Re-selecting the current
  ;; mode must not overwrite the saved preference or a user's manual REC toggle.

  (when (> mode 2)
    (ak/return))
  (when (ak/!= workspace-mode mode)
    (when (ak/== workspace-mode 1)
      (set! record-enabled workspace-record-prior))
    (when (ak/== mode 1)
      (set-state! [workspace-record-prior record-enabled
                   record-enabled 1])))
  ;; Arm state, audio devices, cursor and capture ownership remain untouched.

  (set-state! [workspace-mode mode
               trim-drag 0
               divider-drag false
               name-focus false
               name-drag false
               route-menu 0
               clicked false]))

(az/defn set-workspace-mode! :- :void [[record? :bool]]
  ;; Preserve the existing two-mode entry point for REPL clients.
  (select-workspace! (if record? 1 0)))

(az/defn update-layout! :- :void []
  (let [^{:var :c_int} width 0
        ^{:var :c_int} height 0]
    (glfw/glfwGetWindowSize studio-window (ak/& width) (ak/& height))
    (when (and (> width 0) (> height 0))
      (set-state! [window-width (ak/floatFromInt width)
                   window-height (ak/floatFromInt height)])
      (set-editor-top! editor-top))))

(az/defn hint! :- :void [[text [:slice-const :u8]]]
  (set! hint-length (ak/min 255 (az/field text len)))
  (ak/memcpy (az/slice hint-text 0 hint-length) (az/slice text 0 hint-length)))

(az/defn inside? :- :bool [[x :f32] [y :f32] [w :f32] [h :f32]]
  (and (>= mouse-x x)
       (< mouse-x (+ x w))
       (>= mouse-y y)
       (< mouse-y (+ y h))))

(az/defn divider-input! :- :void []
  (when (ak/== workspace-mode 1)
    (ak/return))
  (when (> route-menu 0)
    (set! divider-drag false)
    (ak/return))
  (when (and clicked (inside? 16.0 (- editor-top 4.0) (main-width) 8.0))
    (set-state! [trim-drag 0
                 name-focus false
                 clicked false])
    (if double-clicked
      (do
        (set-editor-top! 444.0)
        (set! divider-drag false))
      (set-state! [divider-grab (- (ak/as :f32 (ak/floatCast mouse-y)) editor-top)
                   divider-drag true])))
  (when divider-drag
    (set-editor-top! (- (ak/as :f32 (ak/floatCast mouse-y)) divider-grab))
    (set! clicked false)
    (when (ak/! mouse-down)
      (set! divider-drag false))))

(az/defn playing-preview? :- :bool []
  (if mix-mode
    (mixer/playing?)
    (and voice-ready
         (< preview-node 1024)
         (ak/!= (audio/ma_sound_is_playing (ak/& (az/index voices voice-slot))) 0))))

(az/defn selected-preview? :- :bool []
  (and (ak/! mix-mode)
       voice-ready
       (ak/== preview-node selected)))

(az/defn paused-preview? :- :bool []
  (and preview-paused
       (or mix-mode voice-ready)))

(az/defn transport-action :- :u32 []
  ;; Global transport owns the loaded sound, regardless of passage selection.
  (if (or (ak/== workspace-mode 0)
          mix-mode
          (playing-preview?)
          (paused-preview?))
    6
    35))

(az/defn pause-preview! :- :void []
  (when (playing-preview?)
    (if mix-mode
      (mixer/pause!)
      (set! _ (audio/ma_sound_stop (ak/& (az/index voices voice-slot)))))
    (set! preview-paused true)))

(az/defn resume-preview! :- :bool []
  (when mix-mode
    (set! mixer/output-index headphones)
    (when (ak/! (mixer/open!))
      (ak/return false))
    (when (>= (mixer/cursor-frame) mixer/duration)
      (mixer/seek! 0))
    (mixer/play!)
    (set! preview-paused false)
    (ak/return true))
  (when (or (ak/! voice-ready)
            (ak/! preview-paused))
    (ak/return false))
  (when (ak/! (prepare-playback!))
    (ak/return false))
  (when (ak/!= (audio/ma_sound_start (ak/& (az/index voices voice-slot))) audio/MA_SUCCESS)
    (ak/return false))
  (set! preview-paused false)
  true)

(az/defn handle-stopped-outputs!
  "Render-thread only. Pause lost outputs without discarding decoded takes or mix PCM."
  :- :u32 []
  (let [^{:var :u32} result 0]
    (when (and playback-ready
               (ak/! playback-interrupted))
      (let [device ((az/field recorder/api ma_engine_get_device)
                     (ak/ptrCast (ak/& playback-engine)))]
        (when (and (ak/!= device ak/null)
                   (ak/== ((az/field recorder/api ma_device_get_state) device)
                          (az/field recorder/api ma_device_state_stopped)))
          (when voice-ready
            (when (and (ak/! mix-mode) (ak/== preview-node selected))
              (set! seek-seconds (cursor-seconds)))
            (set! _ (audio/ma_sound_stop (ak/& (az/index voices voice-slot))))
            (when (ak/! mix-mode)
              (set! preview-paused true)))
          (set! playback-interrupted true)
          (ak/atomicStore :u32 (ak/& playback-level) 0 :.release)
          (set! result (| result 1)))))
    (when (mixer/handle-stopped-output!)
      (when mix-mode
        (set! preview-paused true))
      (set! result (| result 2)))
    result))

(az/defn position! :- :void [[seconds :f32]]
  (when mix-mode
    (mixer/seek! (ak/intFromFloat (* 48000.0 (ak/max 0.0 seconds))))
    (ak/return))
  (set! seek-seconds (ak/max 0.0 (ak/min take-seconds seconds)))
  (when (and voice-ready (ak/== preview-node selected))
    (seek-preview!)))

(az/defn pan! :- :void [[seconds :f32]]
  (set-state! [timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (+ timeline-start seconds)))
               follow-playhead false]))

(az/defn track-offset-at :- :u32 [[y :f32] [count :u32]]
  (let [rows (ak/as :f32 (ak/floatFromInt (visible-row-count)))
        total (ak/as :f32 (ak/floatFromInt (ak/max (visible-row-count) count)))
        height (ak/max 18.0 (* (track-height) (/ rows total)))
        fraction (ak/max 0.0 (ak/min 1.0 (/ (- y 169.0 bar-grab) (ak/max 1.0 (- (track-height) height)))))]
    (ak/intFromFloat (+ 0.5 (* (- total rows) fraction)))))

(az/defn zoom-at! :- :void [[factor :f32] [x :f32]]
  (let [anchor (time-at x)
        ratio (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) (timeline-width))))]
    (zoom! factor)
    (set! timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (- anchor (* ratio timeline-seconds))))))
  (set! follow-playhead false))

(az/defn scroll-by! :- :void [[dx :f32] [dy :f32] [zoom? :bool] [horizontal? :bool]]
  (when (> route-menu 0)
    (ak/return))
  (when (and (ak/== workspace-mode 2)
             (inside? 16.0 143.0 (main-width) (track-height)))
    (when (ak/!= (ak/as :u32 (ak/intFromFloat track-scroll)) track-offset)
      (set! track-scroll (ak/floatFromInt track-offset)))
    (set-state! [track-scroll (ak/max 0.0 (ak/min
                                          (ak/as :f32 (ak/floatFromInt
                                            (- (ak/max (visible-row-count) scene/passage-entity-count)
                                               (visible-row-count))))
                                          (- track-scroll dy)))
                 track-offset (ak/intFromFloat track-scroll)])
    (ak/return))
  (when (ak/== workspace-mode 1)
    (cond
      (inside? 242.0 204.0 (timeline-width) (record-pane-height))
      (set! record-scroll (ak/max 0.0 (ak/min (ak/max 0.0 (- record-text-height (record-pane-height)))
                                              (- record-scroll (* dy 24.0)))))
      (inside? 16.0 150.0 220.0 (- window-height 220.0))
      (do
        (when (ak/!= (ak/as :u32 (ak/intFromFloat track-scroll)) track-offset)
          (set! track-scroll (ak/floatFromInt track-offset)))
        (set-state! [track-scroll (ak/max 0.0 (ak/min
                                               (ak/as :f32 (ak/floatFromInt (- (ak/max (record-row-count) scene/passage-entity-count) (record-row-count))))
                                               (- track-scroll dy)))
                     track-offset (ak/intFromFloat track-scroll)])))
    (ak/return))
  (cond
    (inside? 242.0 (editor-y 489.0) (timeline-width) (+ (editor-text-height) 9.0))
    (set! focus-scroll (ak/max 0.0 (ak/min (ak/max 0.0 (- focus-height (editor-text-height))) (- focus-scroll (* dy 20.0)))))
    (inside? 16.0 143.0 (main-width) (editor-y 299.0))
    (cond
      (and (ak/== page 0) zoom?)
      (zoom-at! (ak/exp (* -0.12 dy)) (ak/floatCast mouse-x))
      (and (ak/== page 0) horizontal?)
      (pan! (* -0.04 dy timeline-seconds))
      :else
      (do
        (when (ak/!= dx 0.0)
          (pan! (* -0.04 dx timeline-seconds)))
        (if (ak/== page 1)
          (set! scroll (ak/max 0.0 (ak/min (ak/max 0.0 (- content-height (editor-y 256.0))) (- scroll (* dy 22.0)))))
          (do
            (when (ak/!= (ak/as :u32 (ak/intFromFloat track-scroll)) track-offset)
              (set! track-scroll (ak/floatFromInt track-offset)))
            (set-state! [track-scroll (ak/max 0.0 (ak/min (ak/as :f32 (ak/floatFromInt (- (ak/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))) (- track-scroll dy)))
                         track-offset (ak/intFromFloat track-scroll)])))))))

(az/defn scrolled! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [dx :f64] [dy :f64]]
  (when (ak/== window ak/null)
    (ak/return))
  (if attached
    (do
      (update-layout!)
      (glfw/glfwGetCursorPos window (ak/& mouse-x) (ak/& mouse-y))
      (scroll-by! (ak/floatCast dx) (ak/floatCast dy)
                  (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_ALT) glfw/GLFW_PRESS)
                      (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_ALT) glfw/GLFW_PRESS))
                  (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_SHIFT) glfw/GLFW_PRESS)
                      (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_SHIFT) glfw/GLFW_PRESS))))
    (when (ak/!= previous-scroll ak/null)
      ((az/unwrap previous-scroll) window dx dy))))

(az/defn mouse-event! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [button :c_int] [action :c_int] [mods :c_int]]
  (when (and attached (ak/== button glfw/GLFW_MOUSE_BUTTON_LEFT))
    (set! mouse-down (ak/== action glfw/GLFW_PRESS))
    (when mouse-down
      (glfw/glfwGetCursorPos window (ak/& press-x) (ak/& press-y))
      (let [now (glfw/glfwGetTime)]
        (set-state! [event-double (and (< (- now last-press-time) 0.32)
                                       (< (ak/abs (- press-x last-press-x)) 5.0)
                                       (< (ak/abs (- press-y last-press-y)) 5.0))
                     last-press-time now
                     last-press-x press-x
                     last-press-y press-y]))
      (set! event-pressed true)))
  (when (and (ak/! attached) (ak/!= previous-mouse ak/null))
    ((az/unwrap previous-mouse) window button action mods)))

(az/defn key-event! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [key :c_int] [scancode :c_int] [action :c_int] [mods :c_int]]
  (when (> route-menu 0)
    (when (ak/!= action glfw/GLFW_RELEASE)
      (cond
        (ak/== key glfw/GLFW_KEY_ESCAPE)
        (set! route-menu 0)
        (ak/== key glfw/GLFW_KEY_DOWN)
        (route-move! true)
        (ak/== key glfw/GLFW_KEY_UP)
        (route-move! false)
        (ak/== key glfw/GLFW_KEY_HOME)
        (do
          (set-state! [route-focus 0
                       route-offset 0]))
        (ak/== key glfw/GLFW_KEY_END)
        (do
          (set-state! [route-focus (- (ak/max 1 (route-total)) 1)
                       route-offset (* (/ route-focus 8) 8)]))
        (ak/== key glfw/GLFW_KEY_ENTER)
        (route-select! route-focus)))
    (ak/return))
  (when (and name-focus (busy?))
    (when (ak/== key glfw/GLFW_KEY_ESCAPE)
      (set! name-focus false))
    (ak/return))
  (when (and attached (ak/!= action glfw/GLFW_RELEASE))
    (if name-focus
      (let [extend? (ak/!= (& mods glfw/GLFW_MOD_SHIFT) 0)
            command? (ak/!= (& mods (| glfw/GLFW_MOD_SUPER glfw/GLFW_MOD_CONTROL)) 0)]
        (cond
          (and command? (ak/== key glfw/GLFW_KEY_Z))
          (name-undo! extend?)
          (and command? (ak/== key glfw/GLFW_KEY_Y))
          (name-undo! true)
          (ak/== key glfw/GLFW_KEY_ESCAPE)
          (set! name-focus false)
          (ak/== key glfw/GLFW_KEY_ENTER)
          (do
            (set! name-focus false)
            (request! 9))
          (and command? (ak/== key glfw/GLFW_KEY_A))
          (do
            (set-state! [name-anchor 0
                         name-caret name-length]))
          (and command? (ak/== key glfw/GLFW_KEY_C))
          (name-copy! false)
          (and command? (ak/== key glfw/GLFW_KEY_X))
          (name-copy! true)
          (and command? (ak/== key glfw/GLFW_KEY_V))
          (when (ak/!= studio-window ak/null)
            (let [text (glfw/glfwGetClipboardString studio-window)]
              (when (ak/!= text ak/null)
                (name-paste! (mem/span text)))))
          (ak/== key glfw/GLFW_KEY_HOME)
          (name-move! 0 extend?)
          (ak/== key glfw/GLFW_KEY_END)
          (name-move! name-length extend?)
          (ak/== key glfw/GLFW_KEY_LEFT)
          (name-move! (if command?
                        0
                        (if (and (ak/! extend?) (ak/!= name-caret name-anchor))
                          (ak/min name-caret name-anchor)
                          (name-previous name-caret))) extend?)
          (ak/== key glfw/GLFW_KEY_RIGHT)
          (name-move! (if command?
                        name-length
                        (if (and (ak/! extend?) (ak/!= name-caret name-anchor))
                          (ak/max name-caret name-anchor)
                          (name-next name-caret))) extend?)
          (ak/== key glfw/GLFW_KEY_BACKSPACE)
          (when (or (> name-caret 0) (ak/!= name-caret name-anchor))
            (name-checkpoint!)
            (when (ak/== name-caret name-anchor)
              (set! name-anchor (name-previous name-caret)))
            (name-delete!))
          (ak/== key glfw/GLFW_KEY_DELETE)
          (when (or (< name-caret name-length) (ak/!= name-caret name-anchor))
            (name-checkpoint!)
            (when (ak/== name-caret name-anchor)
              (set! name-anchor (name-next name-caret)))
            (name-delete!))))
      (when (ak/== action glfw/GLFW_PRESS)
        (cond
          (and (ak/== key glfw/GLFW_KEY_Z)
               (ak/!= (& mods (| glfw/GLFW_MOD_SUPER glfw/GLFW_MOD_CONTROL)) 0))
          (request! (if (ak/!= (& mods glfw/GLFW_MOD_SHIFT) 0)
                      28
                      27))
          (ak/== key glfw/GLFW_KEY_SPACE)
          (if (busy?)
            (ak/atomicStore :u32 (ak/& pending) 2 :.release)
            (request! (transport-action)))
          (ak/== key glfw/GLFW_KEY_F1)
          (glfw/glfwFocusWindow scene/window)
          (ak/== key glfw/GLFW_KEY_F2)
          (select-workspace! (mod (+ workspace-mode 1) 3))
          (ak/== key glfw/GLFW_KEY_ESCAPE)
          (ak/atomicStore :u32 (ak/& pending) 2 :.release)
          (ak/== key glfw/GLFW_KEY_HOME)
          (when (ak/! (busy?))
            (position! 0.0))
          (ak/== key glfw/GLFW_KEY_EQUAL)
          (zoom-at! 0.8 (timeline-center))
          (ak/== key glfw/GLFW_KEY_MINUS)
          (zoom-at! 1.25 (timeline-center))))))
  (when (and (ak/! attached) (ak/!= previous-key ak/null))
    ((az/unwrap previous-key) window key scancode action mods)))

(az/defn node-id :- [:slice-const :u8] [[index :u32]]
  (let [node (ak/& (az/index (az/field (az/index scene/stories scene/active-story) nodes) index))]
    (az/slice (az/field node id) 0 (az/field node id_len))))

(az/defn node-revision :- :u32 [[index :u32]]
  (az/field (az/index (az/field (az/index scene/stories scene/active-story) nodes) index) revision))

(az/defn node-speaker :- :u32 [[index :u32]]
  (az/field (az/index (az/field (az/index scene/stories scene/active-story) nodes) index) speaker))

(az/defstruct PassageFreshness {:layout :extern}
  [[id [:array 64 :u8]] [id-length :u32] [revision :u32] [status :u32]])

(az/defvar passage-freshness [:array 1024 PassageFreshness]
  (mem/zeroes (az/type [:array 1024 PassageFreshness])))

(az/defn set-passage-freshness! :- :void
  [[index :u32] [id [:slice-const :u8]] [revision :u32] [status :u32]]
  (when (or (>= index scene/passage-entity-count)
            (> (az/field id len) 64)
            (ak/!= revision (node-revision index))
            (ak/! (mem/eql (az/type :u8) id (node-id index))))
    (ak/return))
  (let [entry (ak/& (az/index passage-freshness index))]
    (ak/memcpy (az/slice (az/field entry id) 0 (az/field id len)) id)
    (set! (az/deref entry)
          (PassageFreshness {:id (az/field entry id)
                             :id-length (ak/intCast (az/field id len))
                             :revision revision
                             :status status}))))

(az/defn passage-freshness-status :- :u32 [[index :u32]]
  (when (>= index scene/passage-entity-count)
    (ak/return 0))
  (when (ak/== (az/field (node-id index) len) 0)
    (ak/return 6))
  (let [entry (az/index passage-freshness index)]
    (when (or (ak/!= (node-revision index) (az/field entry revision))
              (ak/! (mem/eql (az/type :u8) (node-id index)
                            (az/slice (az/field entry id) 0 (az/field entry id-length)))))
      (ak/return 0))
    (az/field entry status)))

(az/defn passage-freshness-label :- [:slice-const :u8] [[index :u32]]
  (let [status (passage-freshness-status index)]
    (cond
      (ak/== status 1) "Needs recording"
      (ak/== status 2) "Changed - review take"
      (ak/== status 3) "Recorded"
      (ak/== status 4) "Unverified - review take"
      (ak/== status 5) "Interrupted - review take"
      (ak/== status 6) "Text only"
      :else "Checking recording...")))

(az/defn passage-status-badge :- [:slice-const :u8] [[status :u32]]
  (cond
    (ak/== status 1) "No take"
    (ak/== status 2) "Review"
    (ak/== status 3) "Recorded"
    (ak/== status 4) "Unverified"
    (ak/== status 5) "Interrupted"
    (ak/== status 6) "Text only"
    :else "Checking"))

(az/defn clip! :- :void [[slot :u32] [seconds :f32] [count :u32]]
  (when (< slot 32)
    (set! (az/field (az/index clip-viewport slot) seconds) seconds)
    (set! (az/field (az/index clip-viewport slot) count) count)))

(az/defn clip-start! :- :void [[slot :u32] [seconds :f32]]
  (when (< slot 32)
    (set! (az/field (az/index clip-viewport slot) start) seconds)))

(az/defn clip-bin! :- :void [[slot :u32] [bin :u32] [value :f32]]
  (when (and (< slot 32) (< bin 128))
    (set! (az/index (az/field (az/index clip-viewport slot) wave) bin) value)))

(az/defn time-at :- :f32 [[x :f32]]
  (+ timeline-start (* (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) (timeline-width)))) timeline-seconds)))

(az/defn zoom! :- :void [[factor :f32]]
  (set-state! [timeline-seconds (ak/max 2.0 (ak/min 60.0 (* timeline-seconds factor)))
               timeline-start (ak/min timeline-start (- 60.0 timeline-seconds))]))

(az/defn trim-at! :- :void [[x :f32]]
  (let [^{:zig/type :u32} percent (ak/intFromFloat (ak/max 0.0 (ak/min 100.0 (* 100.0 (/ (- x 242.0) (timeline-width))))))]
    (when (ak/== trim-drag 1)
      (set! trim-in (ak/min (- trim-out 1) percent)))
    (when (ak/== trim-drag 2)
      (set! trim-out (ak/max (+ trim-in 1) percent)))))

(az/defn cursor-seconds :- :f32 []
  (when mix-mode
    (ak/return (/ (ak/as :f32 (ak/floatFromInt (mixer/cursor-frame))) 48000.0)))
  (when (ak/! voice-ready)
    (ak/return seek-seconds))
  (let [^{:var :u64} cursor 0]
    (set! _ (audio/ma_sound_get_cursor_in_pcm_frames
             (ak/& (az/index voices voice-slot)) (ak/& cursor)))
    (/ (ak/as :f32 (ak/floatFromInt cursor)) 48000.0)))

(az/defn selected-take-cursor :- :f32 [[audio-position :f32]]
  ;; A different passage (or the mix) may still be playing. Its clock does not
  ;; belong on this selected take's waveform. Reuse the one per-frame sample.

  (if (and (ak/! mix-mode)
           voice-ready
           (ak/== preview-node selected))
    audio-position
    seek-seconds))

(az/defn pixel-align-x :- :f32 [[raw :f32]]
  (let [scale (ak/max 1.0 framebuffer-scale)]
    (/ (ak/floor (+ 0.5 (* raw scale))) scale)))

(az/defn playhead-x :- :f32 [[seconds :f32]]
  (pixel-align-x (+ 242.0 (* (timeline-width) (/ (- seconds timeline-start) timeline-seconds)))))

(az/defn take-playhead-x :- :f32 [[seconds :f32] [duration :f32]]
  (let [span (- (timeline-width) 1.0)
        scale (ak/max 1.0 framebuffer-scale)
        right (/ (ak/floor (* (+ 242.0 span) scale)) scale)]
    (ak/min right (pixel-align-x (+ 242.0 (* span
                                             (ak/max 0.0 (ak/min 1.0 (/ seconds (ak/max 0.00001 duration))))))))))

(az/defn seek-preview! :- :void []
  (when voice-ready
    (set! _ (audio/ma_sound_seek_to_pcm_frame
             (ak/& (az/index voices voice-slot))
             (ak/intFromFloat (* 48000.0 (ak/max 0.0 (ak/min take-seconds seek-seconds))))))))

(az/defn ui-text! :- :void
  [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32] [rgb :u32]]
  (let [s (* scale 1.5)
        ^{:var :usize} index 0
        ^{:var :f32} cursor x]
    (ak/while (< index (az/field text len))
      (let [code (scene/glyph-code! text (ak/& index))]
        (scene/quad! (- cursor (* 3.0 s)) (+ y (- s scale)) (* 62.0 s) (* 48.0 s)
                     (+ 1025.0 (ak/as :f32 (ak/floatFromInt (* (mod code 16) 64))))
                     (+ 737.0 (ak/as :f32 (ak/floatFromInt (* (ak/divTrunc code 16) 50))))
                     62.0 48.0 rgb 1.0 0.0)
        (set! cursor (+ cursor (* (az/index scene/ui-glyph-advances code) s)))))))

(az/defn ui-text-width :- :f32 [[text [:slice-const :u8]] [scale :f32]]
  (let [^{:var :usize} index 0
        ^{:var :f32} width 0.0]
    (ak/while (< index (az/field text len))
      (let [code (scene/glyph-code! text (ak/& index))]
        (set! width (+ width (* (az/index scene/ui-glyph-advances code) scale 1.5)))))
    width))

(az/defn number! :- :void [[value :u32] [x :f32] [y :f32] [scale :f32]]
  (let [^{:var [:array 10 :u8]} digits ak/undefined
        ^{:var :usize} start 10
        ^{:var :u32} n value]
    (ak/while true
      (set! start (- start 1))
      (set! (az/index digits start) (ak/intCast (+ 48 (mod n 10))))
      (set! n (/ n 10))
      (when (ak/== n 0)
        (ak/break)))
    (ui-text! (az/slice digits start 10) x y scale 0x26364a)))

(az/defn label! :- :void [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [color :u32]]
  (let [^{:var :usize} end (az/field text len)]
    (ak/while (and (> end 0)
                   (> (ui-text-width (az/slice text 0 end) 0.24) width))
      (set! end (- end 1))
      (ak/while (and (> end 0)
                     (>= (az/index text end) 128)
                     (< (az/index text end) 192))
        (set! end (- end 1))))
    (ui-text! (az/slice text 0 end) x y 0.24 color)))

(az/defn seconds! :- :void [[seconds :f32] [x :f32] [y :f32] [scale :f32]]
  (let [^{:zig/type :u32} tenths (ak/intFromFloat (+ 0.5 (* 10.0 (ak/max 0.0 seconds))))
        ^{:var [:array 12 :u8]} digits ak/undefined
        ^{:var :usize} start 10
        ^{:var :u32} n (/ tenths 10)]
    (set! (az/index digits 10) 46)
    (set! (az/index digits 11) (ak/intCast (+ 48 (mod tenths 10))))
    (ak/while true
      (set! start (- start 1))
      (set! (az/index digits start) (ak/intCast (+ 48 (mod n 10))))
      (set! n (/ n 10))
      (when (ak/== n 0)
        (ak/break)))
    (ui-text! (az/slice digits start 12) x y scale 0x26364a)))

(az/defn details! :- :void [[text [:slice-const :u8]]]
  (set! details-length (ak/min 511 (az/field text len)))
  (ak/memcpy (az/slice details 0 details-length) (az/slice text 0 details-length)))

(az/defn name! :- :void [[text [:slice-const :u8]]]
  (set-state! [name-history-position 0
               name-history-end 0
               name-length (ak/min 120 (az/field text len))])
  (ak/while (and (> name-length 0)
                 (< name-length (az/field text len))
                 (ak/== (& (az/index text name-length) 192) 128))
    (set! name-length (- name-length 1)))
  (ak/memcpy (az/slice edit-name 0 name-length) (az/slice text 0 name-length))
  (set-state! [name-caret name-length
               name-anchor name-length
               name-view 0]))

(az/defn entered-name :- [:slice-const :u8] []
  (az/slice edit-name 0 name-length))

(az/defn- reset-take-name! :- :void [[text [:slice-const :u8]]]
  ;; An edit belongs to its previous take, never the newly selected target.
  (set! name-focus false)
  (name! text))

(az/defn set-wave! :- :void [[index :u32] [value :f32]]
  (when (< index 128)
    (set! (az/index wave index) value)))

(az/defn waveform-owner! :- :void [[id [:slice-const :u8]]]
  (when (> (az/field id len) 64) (ak/return))
  (ak/memcpy (az/slice waveform-owner 0 (az/field id len)) id)
  (set-state! [waveform-owner-length (az/field id len)
               waveform-uploaded true]))

(az/defn selected-waveform? :- :bool []
  (and waveform-uploaded
       (or (showing-capture-script?) (< selected scene/passage-entity-count))
       (mem/eql (az/type :u8) (waveform-passage-id)
                (az/slice waveform-owner 0 waveform-owner-length))))

(az/defn selected-take-seconds :- :f32 []
  (if (selected-waveform?) take-seconds 0.0))

(az/defn take-editable? :- :bool []
  (and (ak/! (busy?))
       (> (selected-take-seconds) 0.0)))

(az/defn typed! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [cp :u32]]
  (when (and (ak/! attached) (ak/!= previous-char ak/null))
    ((az/unwrap previous-char) window cp))
  (when (and attached
             name-focus
             (ak/! (busy?))
             (>= cp 32)
             (ak/!= cp 127)
             (<= cp 1114111)
             (or (< cp 55296) (> cp 57343)))
    (let [^{:zig/type :usize} n (if (< cp 128)
                                  1
                                  (if (< cp 2048)
                                    2
                                    (if (< cp 65536)
                                      3
                                      4)))
          removed (- (ak/max name-caret name-anchor) (ak/min name-caret name-anchor))]
      (when (> (+ (- name-length removed) n) 120)
        (ak/return))
      (name-checkpoint!)
      (name-delete!)
      (dotimes [i (- name-length name-caret)]
        (let [source (- name-length i 1)]
          (set! (az/index edit-name (+ source n)) (az/index edit-name source))))
      (set! (az/index edit-name name-caret)
            (ak/intCast (if (ak/== n 1)
                          cp
                          (if (ak/== n 2)
                            (+ 192 (/ cp 64))
                            (if (ak/== n 3)
                              (+ 224 (/ cp 4096))
                              (+ 240 (/ cp 262144)))))))
      (dotimes [i (- n 1)]
        (let [^{:zig/type :u32} divisor (if (ak/== (- n i) 4)
                                          4096
                                          (if (ak/== (- n i) 3)
                                            64
                                            1))]
          (set! (az/index edit-name (+ name-caret i 1)) (ak/intCast (+ 128 (mod (/ cp divisor) 64))))))
      (set-state! [name-length (+ name-length n)
                   name-caret (+ name-caret n)
                   name-anchor name-caret]))))

(az/defn name-copy! :- :void [[cut? :bool]]
  (when (or (ak/== studio-window ak/null) (ak/== name-anchor name-caret))
    (ak/return))
  (let [a (ak/min name-anchor name-caret)
        b (ak/max name-anchor name-caret)
        ^{:var [:array 128 :u8]} text (mem/zeroes (az/type [:array 128 :u8]))]
    (ak/memcpy (az/slice text 0 (- b a)) (az/slice edit-name a b))
    (glfw/glfwSetClipboardString studio-window (ak/& text)))
  (when cut?
    (name-checkpoint!)
    (name-delete!)))

(az/defn name-paste! :- :void [[text [:slice-const :u8]]]
  (when (ak/! (unicode/utf8ValidateSlice text))
    (ak/return))
  (set-state! [name-batch true
               name-batch-recorded false])
  (ak/defer (do
              (set-state! [name-batch false
                           name-batch-recorded false])))
  (let [^{:var :usize} i 0]
    (ak/while (< i (az/field text len))
      (let [n (catch (unicode/utf8ByteSequenceLength (az/index text i)) (ak/return))
            cp (catch (unicode/utf8Decode (az/slice text i (+ i n))) (ak/return))
            bytes (if (or (ak/== cp 10)
                          (ak/== cp 13)
                          (ak/== cp 9))
                    1
                    n)
            removed (- (ak/max name-caret name-anchor) (ak/min name-caret name-anchor))]
        (when (> (+ (- name-length removed) bytes) 120)
          (ak/break))
        (typed! studio-window (if (or (ak/== cp 10)
                                      (ak/== cp 13)
                                      (ak/== cp 9))
                                32
                                cp))
        (set! i (+ i n))))))

(az/defn name-hit :- :usize [[x :f64]]
  (let [^{:var :usize} p name-view]
    (ak/while (< p name-length)
      (let [next (name-next p)
            a (ui-text-width (az/slice edit-name name-view p) 0.24)
            b (ui-text-width (az/slice edit-name name-view next) 0.24)]
        (when (< (- x 36.0) (/ (+ a b) 2.0))
          (ak/break))
        (set! p next)))
    p))

(az/defn name-drag-to!
  "Extend a draft selection, with time-based edge scrolling independent of FPS."
  :- :void [[x :f64] [elapsed :f64]]
  (let [left? (< x 36.0)
        right? (> x 208.0)
        overshoot (if left? (- 36.0 x) (ak/max 0.0 (- x 208.0)))
        interval (ak/max 0.015 (/ 0.08 (+ 1.0 (/ overshoot 24.0))))]
    (if (or left? right?)
      (do
        (set! name-drag-elapsed (+ name-drag-elapsed (ak/min 0.05 (ak/max 0.0 elapsed))))
        (ak/while (>= (+ name-drag-elapsed 0.000000001) interval)
          (set! name-drag-elapsed (ak/max 0.0 (- name-drag-elapsed interval)))
          (if left?
            (set! name-view (name-previous name-view))
            (when (> (ui-text-width (az/slice edit-name name-view name-length) 0.24) 172.0)
              (set! name-view (name-next name-view))))))
      (set! name-drag-elapsed 0.0))
    ;; Hit-test only the visible field, not all offscreen text at the raw pointer.
    (name-move! (name-hit (ak/max 36.0 (ak/min 208.0 x))) true)))

(az/defn name-field! :- :void []
  (scene/rect! 28.0 (bottom-y 621.0) 192.0 28.0 (if name-focus
                                                  0xffffff
                                                  0xeef2f7) 0.0)
  (when (and clicked
             (ak/! (busy?))
             (inside? 28.0 (bottom-y 621.0) 192.0 28.0))
    (set! name-focus true)
    (name-move! (name-hit mouse-x) false)
    (set-state! [name-drag (ak/! double-clicked)
                 name-drag-elapsed 0.0
                 name-pointer-time (glfw/glfwGetTime)])
    (when double-clicked
      (set-state! [name-anchor 0
                   name-caret name-length])))
  (let [now (glfw/glfwGetTime)]
    (when (and name-drag
               mouse-down
               (ak/! (busy?)))
      (name-drag-to! mouse-x (- now name-pointer-time)))
    (set! name-pointer-time now))
  (when (ak/! mouse-down)
    (set-state! [name-drag false
                 name-drag-elapsed 0.0]))
  (when name-focus
    (set! name-view (ak/min name-view name-caret))
    (ak/while (> (ui-text-width (az/slice edit-name name-view name-caret) 0.24) 172.0)
      (set! name-view (name-next name-view))))
  (let [^{:var :usize} end name-view]
    (ak/while (< end name-length)
      (let [next (name-next end)]
        (when (> (ui-text-width (az/slice edit-name name-view next) 0.24) 176.0)
          (ak/break))
        (set! end next)))
    (when (and name-focus (ak/!= name-caret name-anchor))
      (let [a (ak/max name-view (ak/min end (ak/min name-caret name-anchor)))
            b (ak/max a (ak/min end (ak/max name-caret name-anchor)))
            x (ui-text-width (az/slice edit-name name-view a) 0.24)]
        (scene/rect! (+ 36.0 x) (bottom-y 623.0) (ui-text-width (az/slice edit-name a b) 0.24) 24.0 0xdce8ff 0.0)))
    (if (and (ak/== name-length 0) (ak/! name-focus))
      (ui-text! "Take / profile name" 36.0 (bottom-y 625.0) 0.24 0x42566b)
      (ui-text! (az/slice edit-name name-view end) 36.0 (bottom-y 625.0) 0.24 0x182637)))
  (when name-focus
    (scene/rect! (+ 36.0 (ui-text-width (az/slice edit-name name-view name-caret) 0.24)) (bottom-y 625.0) 1.0 20.0 0x356cd5 0.0))
  (when (inside? 28.0 (bottom-y 621.0) 192.0 28.0)
    (glfw/glfwSetCursor studio-window text-cursor)
    (hint! "Name: Cmd/Ctrl+Z undo, Shift+Z redo; A/C/X/V; Enter saves")))

(az/defn waveform! :- :void [[y :f32]]
  (scene/rect! 242.0 y (timeline-width) 65.0 0xf3f6fa 0.0)
  (when (ak/! (selected-waveform?))
    (ui-text! "Loading selected take..." 254.0 (+ y 24.0) 0.23 0x687787)
    (ak/return))
  (when (and (ak/== capture-phase 0)
             (<= take-seconds 0.0))
    (ui-text! "No take yet. Record this passage to hear and edit it."
              254.0 (+ y 24.0) 0.23 0x687787)
    (ak/return))
  (let [^{:var :f32} maximum 0.00001]
    (dotimes [i 128]
      (set! maximum (ak/max maximum (az/index wave i))))
    (dotimes [i 128]
      (let [height (* 55.0 (/ (az/index wave i) maximum))
            x (+ 244.0 (* (/ (- (timeline-width) 2.0) 128.0) (ak/as :f32 (ak/floatFromInt i))))]
        (scene/rect! x (+ y (/ (- 65.0 height) 2.0)) 2.0 (ak/max 1.0 height)
                     (if (and (ak/== capture-phase 2)
                              (mem/eql (az/type :u8) (waveform-passage-id) (captured-id)))
                       0xc63535
                       (if (or (< (* i 100) (* trim-in 128)) (> (* i 100) (* trim-out 128)))
                         0xd9e7ff
                         0x356cd5)) 0.0))))
  (when (and (ak/!= workspace-mode 1) (ak/== capture-phase 0))
    (scene/rect! (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-in)))) y 3.0 65.0 0x356cd5 0.0)
    (scene/rect! (ak/min (content-x 881.0) (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-out))))) y 3.0 65.0 0x356cd5 0.0))
  (when (and (ak/== workspace-mode 1)
             (ak/== capture-phase 0)
             (> take-seconds 0.0))
    (scene/rect! (take-playhead-x (selected-take-cursor frame-cursor) take-seconds) y 1.0 65.0 0x356cd5 0.0)))

(az/defn click-at!
  "Development QA input, consumed by the exact same hit-testing as physical clicks."
  :- :void [[x :f64] [y :f64]]
  (set-state! [test-x x
               test-y y
               test-click true]))

(az/defn busy? :- :bool []
  (ak/!= (ak/atomicLoad :u8 (ak/& busy) :.acquire) 0))

(az/defn status! :- :void [[text [:slice-const :u8]]]
  (set! status-length (ak/min 255 (az/field text len)))
  (ak/memcpy (az/slice status-text 0 status-length) (az/slice text 0 status-length)))

(az/defn alert! :- :void [[text [:slice-const :u8]]]
  (set! alert-length (ak/min 255 (az/field text len)))
  (ak/memcpy (az/slice alert-text 0 alert-length) (az/slice text 0 alert-length)))

(az/defn current-alert :- [:slice-const :u8] []
  (az/slice alert-text 0 alert-length))

(az/defn meter-labels! :- :void [[input [:slice-const :u8]] [returned [:slice-const :u8]]]
  (set-state! [input-meter-length (ak/min 63 (az/field input len))
               return-meter-length (ak/min 63 (az/field returned len))])
  (ak/memcpy (az/slice input-meter-text 0 input-meter-length) (az/slice input 0 input-meter-length))
  (ak/memcpy (az/slice return-meter-text 0 return-meter-length) (az/slice returned 0 return-meter-length)))

(az/defn begin-capture-presentation! :- :void []
  ;; Publish one coherent frame immediately, before the worker's next meter /
  ;; waveform refresh. Never recolor the previous saved take as new live PCM.
  (set-state! [busy 1
               capture-phase 2
               take-seconds 0.0])
  (dotimes [i 128]
    (set-wave! (ak/intCast i) 0.0))
  (waveform-owner! (captured-id))
  (meter-labels! (if (effects-pass?)
                  "Take send: waiting for signal"
                  "Input: waiting for signal")
                (if (capture-fx?)
                  "FX: waiting for signal"
                  "FX: bypassed (Dry mode)"))
  (alert! ""))

(az/defn- rounded-rect! :- :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [radius :f32] [color :u32]]
  (let [r (ak/min radius (/ (ak/min w h) 2.0))]
    (scene/rect! (+ x r) y (- w (* 2.0 r)) h color 0.0)
    (scene/rect! x (+ y r) r (- h (* 2.0 r)) color 0.0)
    (scene/rect! (- (+ x w) r) (+ y r) r (- h (* 2.0 r)) color 0.0)
    (dotimes [corner 4]
      (let [left? (or (ak/== corner 0) (ak/== corner 3))
            cx (if left?
                 (+ x r)
                 (- (+ x w) r))
            cy (if (< corner 2)
                 (+ y r)
                 (- (+ y h) r))
            base (+ 3.14159265 (* 1.57079633 (ak/as :f32 (ak/floatFromInt corner))))]
        (dotimes [segment 6]
          (let [a (+ base (* 0.26179939 (ak/as :f32 (ak/floatFromInt segment))))
                b (+ a 0.26179939)]
            (icon-triangle! cx cy
                            (+ cx (* r (ak/cos a))) (+ cy (* r (ak/sin a)))
                            (+ cx (* r (ak/cos b))) (+ cy (* r (ak/sin b)))
                            color)))))))

(az/defn- control-surface! :- :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [fill :u32] [border :u32]]
  (rounded-rect! x y w h 5.0 border)
  (rounded-rect! (+ x 1.0) (+ y 1.0) (- w 2.0) (- h 2.0) 4.0 fill))

(az/defn button! :- :bool [[label [:slice-const :u8]] [x :f32] [y :f32] [w :f32]]
  (let [hover (and (>= mouse-x x)
                   (< mouse-x (+ x w))
                   (>= mouse-y y)
                   (< mouse-y (+ y 28.0)))]
    (control-surface! x y w 28.0
                      (if hover
                        0xf0f5ff
                        0xffffff)
                      (if hover
                        0x8aa9db
                        0xdce2ea))
    (label! label (+ x 8.0) (+ y 4.0) (- w 12.0) 0x182637)
    (when hover
      (glfw/glfwSetCursor studio-window hand-cursor)
      (hint! label))
    (and hover clicked)))

(az/defn enabled-button! :- :bool
  [[label [:slice-const :u8]] [x :f32] [y :f32] [w :f32] [enabled :bool] [reason [:slice-const :u8]]]
  (when enabled
    (ak/return (button! label x y w)))
  (rounded-rect! x y w 28.0 5.0 0xf3f5f8)
  (label! label (+ x 8.0) (+ y 4.0) (- w 12.0) 0x78869a)
  (when (inside? x y w 28.0)
    (hint! reason))
  false)

(az/defn icon-triangle! :- :void
  [[x1 :f32] [y1 :f32] [x2 :f32] [y2 :f32] [x3 :f32] [y3 :f32] [rgb :u32]]
  (scene/vertex! x1 y1 0.0 0.0 rgb 0.0 0.0)
  (scene/vertex! x2 y2 0.0 0.0 rgb 0.0 0.0)
  (scene/vertex! x3 y3 0.0 0.0 rgb 0.0 0.0))

(az/defn icon-circle! :- :void [[x :f32] [y :f32] [radius :f32] [rgb :u32]]
  (dotimes [i 32]
    (let [a (* 0.19634954 (ak/as :f32 (ak/floatFromInt i)))
          b (+ a 0.19634954)]
      (icon-triangle! x y (+ x (* radius (ak/cos a))) (+ y (* radius (ak/sin a)))
                      (+ x (* radius (ak/cos b))) (+ y (* radius (ak/sin b))) rgb))))

(az/defn icon-button! :- :bool [[kind :u32] [label [:slice-const :u8]] [x :f32] [y :f32]
                                [w :f32] [enabled :bool] [active :bool]]
  (let [hover (inside? x y w 30.0)
        ^{:zig/type :u32} color (cond
                                  active
                                  0xffffff
                                  enabled
                                  0x182637
                                  :else
                                  0x78869a)
        ^{:zig/type :u32} background (cond
                                       (and active (ak/== kind 4))
                                       0xc94747
                                       active
                                       0x356cd5
                                       (ak/! enabled)
                                       0xf3f5f8
                                       hover
                                       0xf0f5ff
                                       :else
                                       0xffffff)]
    (control-surface! x y w 30.0 background
                      (if active
                        background
                        0xdce2ea))
    (cond
      (ak/== kind 0)
      (icon-triangle! (+ x 10.0) (+ y 7.0) (+ x 24.0) (+ y 15.0) (+ x 10.0) (+ y 23.0) color)
      (ak/== kind 1)
      (do
        (scene/rect! (+ x 9.0) (+ y 7.0) 4.0 16.0 color 0.0)
        (scene/rect! (+ x 18.0) (+ y 7.0) 4.0 16.0 color 0.0))
      (ak/== kind 2)
      (scene/rect! (+ x 9.0) (+ y 8.0) 13.0 13.0 color 0.0)
      (ak/== kind 3)
      (do
        (scene/rect! (+ x 7.0) (+ y 7.0) 3.0 16.0 color 0.0)
        (icon-triangle! (+ x 24.0) (+ y 7.0) (+ x 24.0) (+ y 23.0) (+ x 12.0) (+ y 15.0) color))
      (ak/== kind 4)
      (do
        (icon-circle! (+ x 16.0) (+ y 15.0) 7.0
                      (cond
                        active
                        0xffffff
                        enabled
                        0xc94747
                        :else
                        0x78869a))
        (when (ak/! active)
          (icon-circle! (+ x 16.0) (+ y 15.0) 5.5 background))))
    (when (> w 45.0)
      (label! label (+ x 32.0) (+ y 5.0) (- w 37.0) color))
    (when hover
      (hint! (if enabled
               label
               (if (busy?)
                 "Processing"
                 "No take for this passage")))
      (when enabled
        (glfw/glfwSetCursor studio-window hand-cursor)))
    (and enabled
         hover
         clicked)))

(az/defn paragraph-line-visible? :- :bool
  [[row :f32] [line-height :f32] [top :f32] [bottom :f32]]
  ;; Inclusive boundaries, with subpixel tolerance for accumulated f32 layout.
  ;; At maximum scroll the last line ends exactly at the pane's bottom.

  (and (>= row (- top 0.01))
       (<= (+ row line-height) (+ bottom 0.01))))

(az/defn- paragraph-text!
  "Clip glyph geometry and atlas coordinates together for pixel-smooth pane scrolling."
  :- :void [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32]
           [left :f32] [top :f32] [right :f32] [bottom :f32] [color :u32]]
  (when (<= scale 0.0)
    (ak/return))
  (let [^{:var :usize} index 0
        ^{:var :f32} cursor x]
    (ak/while (< index (az/field text len))
      (let [code (scene/glyph-code! text (ak/& index))
            glyph-x (- cursor (* 3.0 scale))
            glyph-y (+ y scale)
            clipped-x (ak/max left glyph-x)
            clipped-y (ak/max top glyph-y)
            clipped-right (ak/min right (+ glyph-x (* 62.0 scale)))
            clipped-bottom (ak/min bottom (+ glyph-y (* 78.0 scale)))]
        (when (and (> clipped-right clipped-x)
                   (> clipped-bottom clipped-y))
          (scene/quad! clipped-x clipped-y
                       (- clipped-right clipped-x) (- clipped-bottom clipped-y)
                       (+ 1.0 (* (ak/as :f32 (ak/floatFromInt (mod code 32))) 64.0)
                          (/ (- clipped-x glyph-x) scale))
                       (+ 1.0 (* (ak/as :f32 (ak/floatFromInt (ak/divTrunc code 32))) 80.0)
                          (/ (- clipped-y glyph-y) scale))
                       (/ (- clipped-right clipped-x) scale)
                       (/ (- clipped-bottom clipped-y) scale)
                       color 1.0 0.0))
        (set! cursor (+ cursor (* (az/index scene/glyph-advances code) scale)))))))

(az/defn paragraph!
  "Wrap complete text and clip glyphs to the pane without snapping scroll to lines."
  :- :f32 [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32]
           [scale :f32] [top :f32] [bottom :f32] [color :u32]]
  (let [^{:var :usize} start 0
        ^{:var :f32} cursor x
        ^{:var :f32} row y
        line-height (* 65.0 scale)]
    (ak/while (< start (az/field text len))
      (let [^{:var :usize} end start]
        (ak/while (and (< end (az/field text len))
                       (ak/!= (az/index text end) 32)
                       (ak/!= (az/index text end) 10))
          (set! end (+ end 1)))
        (let [word (az/slice text start end)
              width-word (scene/text-width word scale)]
          (when (and (> cursor x) (> (+ cursor width-word) (+ x width)))
            (set-state! [row (+ row line-height)
                         cursor x]))
          (if (> width-word width)
            ;; Long URLs/identifiers have no spaces. Break only between UTF-8
            ;; code points, so their tail remains reachable instead of overflowing.
            (let [^{:var :usize} glyph-start start
                  ^{:var :usize} glyph-end start]
              (ak/while (< glyph-start end)
                (let [code (scene/glyph-code! text (ak/& glyph-end))
                      advance (* (az/index scene/glyph-advances code) scale)]
                  (when (and (> cursor x) (> (+ cursor advance) (+ x width)))
                    (set-state! [row (+ row line-height)
                                 cursor x]))
                  (when (and (< row bottom) (> (+ row (* 79.0 scale)) top))
                    (paragraph-text! (az/slice text glyph-start glyph-end)
                                     cursor row scale x top (+ x width) bottom color))
                  (set-state! [cursor (+ cursor advance)
                               glyph-start glyph-end]))))
            (do
              (when (and (< row bottom)
                         (> (+ row (* 79.0 scale)) top))
                (paragraph-text! word cursor row scale x top (+ x width) bottom color))
              (set! cursor (+ cursor width-word))))
          (set! cursor (+ cursor (scene/text-width " " scale))))
        (when (and (< end (az/field text len)) (ak/== (az/index text end) 10))
          (set-state! [row (+ row line-height)
                       cursor x]))
        (set! start (+ end 1))))
    (+ row line-height)))

(az/defn selected-id :- [:slice-const :u8] []
  (let [node (ak/& (az/index (az/field (az/index scene/stories scene/active-story) nodes) selected))]
    (az/slice (az/field node id) 0 (az/field node id_len))))

(az/defn note-take! :- :bool [[id [:slice-const :u8]]]
  (when (or (ak/== (az/field id len) 0) (> (az/field id len) 64))
    (ak/return false))
  (let [^{:var [:array 65 :u8]} name (mem/zeroes (az/type [:array 65 :u8]))]
    (ak/memcpy (az/slice name 0 (az/field id len)) id)
    (let [entity (flecs/ecs_lookup scene/world (ak/& name))]
      (when (ak/== entity 0)
        (ak/return false))
      (let [raw (flecs/ecs_get_mut_id scene/world entity scene/passage-component)]
        (when (ak/== raw ak/null)
          (ak/return false))
        (let [state (az/cast raw [:* scene/PassageState])]
          (set! (az/field state takes) (+ (az/field state takes) 1))
          true)))))

(az/defn take-action! :- :u32 []
  (ak/atomicRmw :u32 (ak/& pending) :.Xchg 0 :.acq_rel))

(az/defn request! :- :void [[action :u32]]
  (when (ak/== (ak/atomicLoad :u8 (ak/& busy) :.acquire) 0)
    (ak/atomicStore :u8 (ak/& busy) 1 :.release)
    (ak/atomicStore :u32 (ak/& pending) action :.release)))

(az/defn recordable-selection? :- :bool []
  (and (< selected scene/passage-entity-count)
       (> (az/field (selected-id) len) 0)))

(az/defn requested-recording-id :- [:slice-const :u8] []
  (az/slice record-request-text 0 record-request-length))

(az/defn request-selected-recording! :- :void []
  (when (or (busy?) (ak/! (recordable-selection?)))
    (ak/return))
  ;; Keep the clicked passage ID, not a row number that Markdown reload can move.
  (let [id (selected-id)]
    (when (> (az/field id len) 64)
      (ak/return))
    (set! record-request-length (az/field id len))
    (ak/memcpy (az/slice record-request-text 0 record-request-length) id))
  (request! 38)
  (set! clicked false))

(az/defn- draw-edit-toolbar! :- :void []
  (when (button! "Tracks" 16.0 104.0 76.0)
    (set! page 0))
  (when (button! "Script" 100.0 104.0 76.0)
    (set! page 1))
  (when (button! "<" 184.0 104.0 36.0)
    (set-state! [track-offset (- track-offset (ak/min track-offset (visible-row-count)))
                 scroll (ak/max 0.0 (- scroll 180.0))]))
  (when (button! ">" 224.0 104.0 36.0)
    (set-state! [track-offset (ak/min (+ track-offset (visible-row-count)) (- (ak/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))
                 scroll (ak/min (ak/max 0.0 (- content-height (editor-y 256.0))) (+ scroll 180.0))]))
  (ui-text! "Takes / seconds" 284.0 108.0 0.24 0x586777)
  (when (button! (if follow-playhead
                   "Follow: on"
                   "Follow: off") (right-x 474.0) 104.0 134.0)
    (set! follow-playhead (ak/! follow-playhead)))
  (when (button! "-" (right-x 620.0) 104.0 36.0)
    (zoom-at! 2.0 (timeline-center)))
  (when (button! "+" (right-x 662.0) 104.0 36.0)
    (zoom-at! 0.5 (timeline-center)))
  (when (button! "All" (right-x 704.0) 104.0 58.0)
    (set-state! [timeline-seconds 60.0
                 timeline-start 0.0]))
  (when (button! "<" (right-x 790.0) 104.0 36.0)
    (pan! (- (/ timeline-seconds 2.0))))
  (when (button! ">" (right-x 832.0) 104.0 36.0)
    (pan! (/ timeline-seconds 2.0)))
  (when (button! (if routing-visible
                   "Hide routing"
                   "Show routing") (right-x 896.0) 104.0 190.0)
    (show-routing! (ak/! routing-visible)))
  (when (inside? (right-x 896.0) 104.0 190.0 28.0)
    (hint! "Show or hide routing. Audio connections and monitoring stay unchanged."))
  (when (and follow-playhead
             (playing-preview?)
             (or (< frame-cursor timeline-start)
                 (> frame-cursor (+ timeline-start (* 0.94 timeline-seconds)))))
    (set! timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (- frame-cursor (* 0.1 timeline-seconds)))))))

(az/defn- draw-timeline-ruler! :- :void []
  (when (and (ak/== page 0)
             (inside? 242.0 140.0 (- (timeline-width) 6.0) 27.0))
    (hint! "Ruler: click or drag to seek")
    (glfw/glfwSetCursor studio-window resize-cursor)
    (when (and clicked (ak/! (busy?)))
      (set! trim-drag 3)
      (position! (time-at (ak/floatCast mouse-x)))
      (set! clicked false)))
  (when (and mix-mode
             (> loop-to timeline-start)
             (< loop-from (+ timeline-start timeline-seconds)))
    (let [x (+ 242.0 (* (timeline-width) (ak/max 0.0 (/ (- loop-from timeline-start) timeline-seconds))))
          end-x (+ 242.0 (* (timeline-width) (ak/min 1.0 (/ (- loop-to timeline-start) timeline-seconds))))]
      (scene/rect! x 167.0 (- end-x x) (editor-y 267.0)
                   (if (az/field (mixer/loop-state) enabled)
                     0xe2f2ee
                     0xe9edf3) 0.0)
      (scene/rect! x 163.0 (- end-x x) 3.0 0x356cd5 0.0)))
  (dotimes [tick 7]
    (let [x (+ 242.0 (* (ak/as :f32 (ak/floatFromInt tick)) (/ (timeline-width) 6.0)))]
      (seconds! (+ timeline-start (* (/ timeline-seconds 6.0) (ak/as :f32 (ak/floatFromInt tick)))) (- x 10.0) 143.0 0.20)
      (scene/rect! x 167.0 1.0 (editor-y 267.0) 0xe0e5ed 0.0))))

(az/defn- draw-track-row! :- :void [[data [:* scene/Story]] [slot :usize]]
  (let [i (+ track-offset (ak/as :u32 (ak/intCast slot)))
        y (+ 169.0 (* 56.0 (ak/as :f32 (ak/floatFromInt slot))))]
    (when (< i (az/field data count))
      (let [node (az/index (az/field data nodes) i)
            active (ak/== selected i)
            capturing (and (ak/== capture-phase 2) (capture-passage? i))
            loaded (and (ak/== track-snapshot track-offset)
                        (< slot track-snapshot-count))
            seconds (if capturing
                      capture-cursor
                      (if loaded
                        (az/field (az/index clip-viewport slot) seconds)
                        0.0))
            start (if (and loaded
                           mix-mode
                           (ak/! capturing))
                    (az/field (az/index clip-viewport slot) start)
                    0.0)]
        (draw-track-labels! i y (az/field node speaker) (> (az/field node id_len) 0) active)
        (draw-track-buttons! i y seconds (> (az/field node id_len) 0))
        (track-row-selection! i y seconds start)
        (draw-track-waveform! slot y seconds start active capturing)
        (when (ak/== seconds 0.0)
          (ui-text! (if (ak/! loaded)
                      "Loading..."
                      (if (> (az/field node id_len) 0)
                        "Not recorded"
                        "Text only")) 252.0 (+ y 11.0) 0.23 0x687787))
        (draw-track-playhead! i y capturing)))))

(az/defn- draw-passage-status! :- :void [[index :u32] [x :f32] [y :f32]]
  (let [status (passage-freshness-status index)
        review? (or (ak/== status 2) (ak/== status 5))
        recorded? (ak/== status 3)
        ^{:zig/type :u32} background (cond
                                      review? 0xffeed9
                                      recorded? 0xe4f2eb
                                      :else 0xe7ebf1)
        ^{:zig/type :u32} foreground (cond
                                      review? 0x855017
                                      recorded? 0x246147
                                      :else 0x46566b)]
    (rounded-rect! x y 76.0 17.0 4.0 background)
    (ui-text! (passage-status-badge status) (+ x 6.0) (+ y 1.0) 0.19 foreground)
    (when (inside? x y 76.0 17.0)
      (hint! (passage-freshness-label index)))))

(az/defn- draw-track-labels! :- :void
  [[i :u32] [y :f32] [speaker :u32] [voiced? :bool] [active :bool]]
  (let [^{:zig/type :u32} background (if active 0xe0ebff 0xf2f4f8)
        ^{:zig/type :u32} accent (if voiced? 0x356cd5 0x8593a8)
        speaker-label (cond
                        (ak/== speaker 86) "LA VOITURE"
                        (ak/== speaker 77) "LA MANGUE"
                        :else "CONTEXT")]
    (scene/rect! 16.0 y 220.0 54.0 background 0.0)
    (scene/rect! 16.0 y 4.0 54.0 accent 0.0)
    (number! (+ i 1) 27.0 (+ y 3.0) 0.24)
    (label! speaker-label 48.0 (+ y 2.0) 113.0 0x182637)
    (label! (scene/story-text i) 27.0 (+ y 34.0) 116.0 0x586777)
    (draw-passage-status! i 150.0 (+ y 34.0))))

(az/defn- draw-track-buttons! :- :void
  [[i :u32] [y :f32] [seconds :f32] [voiced? :bool]]
  (when voiced?
    (when (icon-button! 4 "Arm / disarm this track" 160.0 (+ y 1.0) 30.0
                        (ak/! (busy?)) (ak/== record-track i))
      (set-state! [record-track (if (ak/== record-track i)
                                  4294967295
                                  i)
                   selected i
                   clicked false])))
  (when (icon-button! (if (and (ak/! mix-mode)
                               (playing-preview?)
                               (ak/== preview-node i))
                        1
                        0)
                      "Play / pause this track" 194.0 (+ y 1.0) 34.0
                      (and (> seconds 0.0) (ak/! (busy?))) (and (playing-preview?) (ak/== preview-node i)))
    (when (ak/!= selected i)
      (set! seek-seconds 0.0))
    (set-state! [selected i
                 focus-scroll 0.0])
    ;; Row playback is always audition, even when the global transport has REC
    ;; enabled. Resuming here must never schedule a microphone recording.
    (request! 35)
    (set! clicked false)))

(az/defn- track-row-selection! :- :void
  [[i :u32] [y :f32] [seconds :f32] [start :f32]]
  (when (and clicked
             (ak/! (busy?))
             (>= mouse-y y)
             (< mouse-y (+ y 54.0))
             (>= mouse-x 16.0)
             (< mouse-x (content-x 878.0)))
    (when (ak/!= selected i)
      (set! seek-seconds 0.0))
    (set-state! [selected i
                 focus-scroll 0.0
                 name-focus false])
    (when (and (>= mouse-x 242.0)
               (> seconds 0.0)
               (>= (time-at (ak/floatCast mouse-x)) start)
               (< (time-at (ak/floatCast mouse-x)) (+ start seconds)))
      (set! take-seconds seconds)
      (position! (time-at (ak/floatCast mouse-x)))
      (if double-clicked
        (request! 26)
        (set! trim-drag 3))))
  (when (and (inside? 242.0 y (- (timeline-width) 6.0) 54.0)
             (> seconds 0.0))
    (hint! "Click: seek. Double-click: play. Option + scroll: zoom.")))

(az/defn- draw-track-waveform! :- :void
  [[slot :usize] [y :f32] [seconds :f32] [start :f32] [active :bool] [capturing :bool]]
  (when (and (> (+ start seconds) timeline-start)
             (< start (+ timeline-start timeline-seconds)))
    (let [a (ak/max 0.0 (/ (- start timeline-start) timeline-seconds))
          end-ratio (ak/min 1.0 (/ (- (+ start seconds) timeline-start) timeline-seconds))
          w (* (timeline-width) (- end-ratio a))
          ^{:zig/type :u32} background (cond
                                        capturing 0xf9dadd
                                        active 0xdce8ff
                                        :else 0xe5ebf3)]
      (scene/rect! (+ 242.0 (* (timeline-width) a)) (+ y 2.0) w 50.0 background 0.0)
      (let [^{:var :f32} peak 0.00001]
        (when capturing
          (dotimes [b 128]
            (set! (az/index (az/field (az/index clip-viewport slot) wave) b)
                  (recorder/wave-bin (capture-fx?) (ak/intCast b)))))
        (dotimes [b 128]
          (set! peak (ak/max peak (az/index (az/field (az/index clip-viewport slot) wave) b))))
        (dotimes [b 128]
          (let [t (+ start (* seconds (/ (ak/as :f32 (ak/floatFromInt b)) 128.0)))
                x (+ 242.0 (* (timeline-width) (/ (- t timeline-start) timeline-seconds)))
                h (* 30.0 (/ (az/index (az/field (az/index clip-viewport slot) wave) b) peak))]
            (when (and (>= x 242.0) (< x (content-x 882.0)))
              (scene/rect! x (+ y 27.0 (- (/ h 2.0))) 2.0 (ak/max 1.0 h) 0x4878c7 0.0))))))))

(az/defn- draw-track-playhead! :- :void [[i :u32] [y :f32] [capturing :bool]]
  (when (and (ak/! mix-mode)
             (or (ak/== selected i)
                 (and voice-ready (ak/== preview-node i))))
    (let [position (if capturing
                     capture-cursor
                     (if (and voice-ready (ak/== preview-node i))
                       frame-cursor
                       seek-seconds))
          x (playhead-x position)]
      (when (and (>= x 242.0) (<= x (content-x 884.0)))
        (scene/rect! x y 2.0 54.0 0x356cd5 0.0)))))

(az/defn- draw-script-browser! :- :void [[data [:* scene/Story]]]
  (let [^{:var :f32} row (- 151.0 scroll)]
    (dotimes [i (az/field data count)]
      (let [top row
            node (az/index (az/field data nodes) i)
            indent (* 4.0 (ak/as :f32 (ak/floatFromInt (ak/min 12 (az/field node indent)))))]
        (set! row (paragraph! (scene/story-text (ak/intCast i)) (+ 28.0 indent) row (- (content-x 834.0) indent) 0.30 150.0 (editor-y 430.0)
                              (if (ak/== i selected)
                                0x235ac0
                                0x26364a)))
        (when (and clicked
                   (ak/! (busy?))
                   (>= mouse-x 16.0)
                   (< mouse-x (content-x 884.0))
                   (>= mouse-y (ak/max top 150.0))
                   (< mouse-y (ak/min row (editor-y 430.0))))
          (set-state! [selected (ak/intCast i)
                       focus-scroll 0.0
                       name-focus false]))
        (set! row (+ row 12.0))))
    (set! content-height (- (+ row scroll) 151.0))
    ;; Reflow can shorten the document after widening the window.
    (set! scroll (ak/max 0.0 (ak/min scroll (ak/max 0.0 (- content-height (editor-y 256.0))))))))

(az/defn- draw-timeline-navigation! :- :void []
  (when mix-mode
    (let [x (playhead-x frame-cursor)]
      (when (and (>= x 242.0) (<= x (content-x 884.0)))
        (scene/rect! x 167.0 2.0 (editor-y 265.0) 0x356cd5 0.0))))
  (let [w (* (timeline-width) (/ timeline-seconds 60.0))
        x (+ 242.0 (* (timeline-width) (/ timeline-start 60.0)))]
    (scene/rect! 242.0 (editor-y 432.0) (timeline-width) 8.0 0xdce3ed 0.0)
    (scene/rect! x (editor-y 432.0) w 8.0 0x7192c9 0.0)
    (when (inside? 242.0 (editor-y 432.0) (timeline-width) 10.0)
      (hint! "Time scrollbar: drag to pan")
      (glfw/glfwSetCursor studio-window hand-cursor)
      (when clicked
        (set-state! [bar-grab (if (and (>= mouse-x x) (< mouse-x (+ x w)))
                                (- (ak/as :f32 (ak/floatCast mouse-x)) x)
                                (/ w 2.0))
                     trim-drag 5]))))
  (let [rows (ak/as :f32 (ak/floatFromInt (visible-row-count)))
        total (ak/as :f32 (ak/floatFromInt (ak/max (visible-row-count) scene/passage-entity-count)))
        h (ak/max 18.0 (* (track-height) (/ rows total)))
        y (+ 169.0 (* (- (track-height) h) (/ (ak/as :f32 (ak/floatFromInt track-offset)) (ak/max 1.0 (- total rows)))))]
    (scene/rect! (content-x 880.0) 169.0 12.0 (track-height) 0xdce3ed 0.0)
    (scene/rect! (content-x 882.0) y 8.0 h 0x7192c9 0.0)
    (when (inside? (content-x 879.0) 169.0 14.0 (track-height))
      (hint! "Tracks: drag or scroll with two fingers")
      (glfw/glfwSetCursor studio-window hand-cursor)
      (when clicked
        (set-state! [bar-grab (if (and (>= mouse-y y) (< mouse-y (+ y h)))
                                (- (ak/as :f32 (ak/floatCast mouse-y)) y)
                                (/ h 2.0))
                     trim-drag 6])))))

(az/defn- draw-take-editor! :- :void []
  ;; The editor is always visible: reading a long passage never hides transport or routing.

  (scene/rect! 16.0 editor-top (main-width) (- window-height editor-top 40.0) 0xffffff 0.0)
  (let [hover (and (ak/== route-menu 0)
                   (inside? 16.0 (- editor-top 4.0) (main-width) 8.0))]
    (scene/rect! 16.0 (- editor-top 2.0) (main-width) 3.0 (if (or hover divider-drag)
                                                            0x356cd5
                                                            0xc8d1df) 0.0)
    (when (or hover divider-drag)
      (when (ak/== vertical-cursor ak/null)
        (set! vertical-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_VRESIZE_CURSOR)))
      (glfw/glfwSetCursor studio-window vertical-cursor)
      (hint! "Drag to resize tracks and editor. Double-click to reset.")))
  (ui-text! "TAKE / EDIT" 28.0 (editor-y 455.0) 0.28 0x356cd5)
  (when (button! "Previous" 28.0 (editor-y 495.0) 88.0)
    (request! 4))
  (when (button! "Next" 126.0 (editor-y 495.0) 94.0)
    (request! 5))
  (when (enabled-button! "Mark A" 28.0 (editor-y 535.0) 192.0
                         (take-editable?) "Select a loaded take to mark for comparison.")
    (request! 10))
  (when (enabled-button! (comparison-label) 28.0 (editor-y 575.0) 192.0
                         (comparison-enabled?) (comparison-hint))
    (request! 11))
  (name-field!)
  (when (enabled-button! "Rename" 28.0 (bottom-y 661.0) 90.0
                         (take-editable?) "Record or select a take before renaming it.")
    (request! 9))
  (when (enabled-button! "Favorite" 126.0 (bottom-y 661.0) 94.0
                         (take-editable?) "Select a loaded take to make it the favorite.")
    (request! 13))
  (label! (selected-id) 242.0 (editor-y 452.0) 444.0 0x356cd5)
  (when (button! "Up" (content-x 724.0) (editor-y 447.0) 70.0)
    (set! focus-scroll (ak/max 0.0 (- focus-scroll 80.0))))
  (when (button! "Down" (content-x 802.0) (editor-y 447.0) 70.0)
    (set! focus-scroll (ak/min (ak/max 0.0 (- focus-height (editor-text-height))) (+ focus-scroll 80.0))))
  (set-state! [focus-height (- (+ focus-scroll (paragraph! (scene/story-text selected) 242.0 (- (editor-y 489.0) focus-scroll)
                                                           (- (timeline-width) 17.0) 0.34 (editor-y 489.0) (bottom-y 594.0) 0x182637)) (editor-y 489.0))
               focus-scroll (ak/max 0.0 (ak/min focus-scroll (ak/max 0.0 (- focus-height (editor-text-height)))))])
  (waveform! (bottom-y 611.0))
  (take-waveform-input!)
  (drag-editor-selection!)
  (draw-trim-controls!))

(az/defn- take-waveform-input! :- :void []
  (when (ak/! (take-editable?))
    (ak/return))
  (when (and clicked
             (ak/! (busy?))
             (>= mouse-x 238.0)
             (<= mouse-x (content-x 888.0))
             (>= mouse-y (bottom-y 607.0))
             (< mouse-y (bottom-y 679.0)))
    (let [x (ak/as :f32 (ak/floatCast mouse-x))
          a (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-in))))
          b (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-out))))]
      (if (< (ak/min (ak/abs (- x a)) (ak/abs (- x b))) 9.0)
        (do
          (set! trim-drag (if (< (ak/abs (- x a)) (ak/abs (- x b)))
                            1
                            2))
          (trim-at! x))
        (do
          (set! trim-drag 4)
          (position! (* take-seconds (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) (timeline-width))))))))))
  (when (inside? 238.0 (bottom-y 607.0) (+ (timeline-width) 8.0) 72.0)
    (let [x (ak/as :f32 (ak/floatCast mouse-x))
          a (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-in))))
          b (+ 242.0 (* (/ (timeline-width) 100.0) (ak/as :f32 (ak/floatFromInt trim-out))))]
      (if (< (ak/min (ak/abs (- x a)) (ak/abs (- x b))) 9.0)
        (do
          (hint! "Edge: trim without changing the source file")
          (glfw/glfwSetCursor studio-window resize-cursor))
        (hint! "Waveform: click or drag to seek")))))

(az/defn- drag-editor-selection! :- :void []
  ;; Timeline pan/scroll remains available while a take loads. Only the
  ;; selected take's trim edges and waveform seek depend on its upload.
  (when (and (ak/! (take-editable?))
             (or (ak/== trim-drag 1)
                 (ak/== trim-drag 2)
                 (ak/== trim-drag 4)))
    (set! trim-drag 0))
  (when (and (> trim-drag 0)
             (or mouse-down clicked)
             (ak/! (busy?)))
    (cond
      (<= trim-drag 2)
      (trim-at! (ak/floatCast mouse-x))
      (ak/== trim-drag 3)
      (position! (time-at (ak/floatCast mouse-x)))
      (ak/== trim-drag 4)
      (position! (* take-seconds (ak/max 0.0 (ak/min 1.0 (/ (- (ak/as :f32 (ak/floatCast mouse-x)) 242.0) (timeline-width))))))
      (ak/== trim-drag 5)
      (do
        (set! timeline-start 0.0)
        (pan! (* 60.0 (/ (- (ak/as :f32 (ak/floatCast mouse-x)) 242.0 bar-grab) (timeline-width)))))
      (ak/== trim-drag 6)
      (set! track-offset (track-offset-at (ak/floatCast mouse-y) scene/passage-entity-count))))
  (when (ak/! mouse-down)
    (set! trim-drag 0)))

(az/defn- draw-trim-controls! :- :void []
  (when (> (selected-take-seconds) 0.0)
    (ui-text! "Drag edges to trim" 242.0 (bottom-y 690.0) 0.22 0x586777)
    (number! trim-in 452.0 (bottom-y 690.0) 0.22)
    (ui-text! "%" 481.0 (bottom-y 690.0) 0.22 0x586777)
    (number! trim-out 518.0 (bottom-y 690.0) 0.22)
    (ui-text! "%" 552.0 (bottom-y 690.0) 0.22 0x586777))
  (when (enabled-button! "Reset" (content-x 594.0) (bottom-y 685.0) 122.0
                         (take-editable?) "Select a loaded take to reset its trim.")
    (set-state! [trim-in 0
                 trim-out 100]))
  (when (enabled-button! "Trim copy" (content-x 726.0) (bottom-y 685.0) 146.0
                         (take-editable?)
                         "Select a loaded take before trimming.")
    (request! 12)))

(az/defn draw-edit-workspace! :- :void []
  (draw-edit-toolbar!)
  (scene/rect! 16.0 143.0 (main-width) (editor-y 291.0) 0xfafbfd 0.0)
  (let [data (ak/& (az/index scene/stories scene/active-story))]
    (if (ak/== page 0)
      (do
        (draw-timeline-ruler!)
        (dotimes [slot (visible-row-count)]
          (draw-track-row! data slot))
        (draw-timeline-navigation!))
      (when (ak/== page 1)
        (draw-script-browser! data))))
  (draw-take-editor!))

(az/defn upload-take-card! :- :void
  [[slot :u32]
   [available? :bool]
   [missing? :bool]
   [chosen? :bool]
   [duration :f32]
   [text [:slice-const :u8]]]
  (when (>= slot 48)
    (ak/return))
  (let [card (ak/& (az/index take-cards slot))
        ^{:var :usize} length (ak/min 127 (az/field text len))]
    ;; Truncate only at a UTF-8 boundary; existing take names remain untouched.
    (ak/while (and (> length 0)
                    (< length (az/field text len))
                    (>= (az/index text length) 128)
                    (< (az/index text length) 192))
      (set! length (- length 1)))
    (set! (az/field card available) available?)
    (set! (az/field card missing) missing?)
    (set! (az/field card chosen) chosen?)
    (set! (az/field card seconds) duration)
    (set! (az/field card label-length) length)
    (ak/memcpy (az/slice (az/field card label) 0 length) (az/slice text 0 length))))

(az/defn upload-take-card-bin! :- :void [[slot :u32] [bin :u32] [peak :f32]]
  (when (and (< slot 48) (< bin 32))
    (set! (az/index (az/field (az/index take-cards slot) bins) bin) peak)))

(az/defn take-card-width :- :f32 []
  (- (/ (timeline-width) 3.0) 8.0))

(az/defn take-card-x :- :f32 [[column :u32]]
  (+ 242.0 (* (/ (timeline-width) 3.0) (ak/as :f32 (ak/floatFromInt column)))))

(az/defn- request-take-card! :- :void [[slot :u32] [audition? :bool]]
  (set-state! [take-grid-action-slot slot
               take-grid-action-revision take-grid-revision])
  (request! (if audition? 37 36))
  (set! clicked false))

(az/defn- draw-take-card-wave! :- :void [[card [:* TakeCard]] [x :f32] [y :f32] [width :f32]]
  (let [^{:var :f32} peak 0.001]
    (dotimes [i 32]
      (set! peak (ak/max peak (az/index (az/field card bins) i))))
    (dotimes [i 32]
      (let [height (ak/max 1.0 (* 19.0 (/ (az/index (az/field card bins) i) peak)))
            position (+ x (* (/ width 32.0) (ak/as :f32 (ak/floatFromInt i))))]
        (scene/rect! position (+ y (* 0.5 (- 20.0 height))) 2.0 height 0x6d8fbe 0.0)))))

(az/defn- draw-take-card! :- :void [[i :u32] [slot :u32] [column :u32] [y :f32]]
  (let [x (take-card-x column)
        width (take-card-width)
        index (+ (* slot 3) column)
        card (ak/& (az/index take-cards index))
        loaded? (and (ak/== take-grid-offset track-offset) (< slot take-grid-rows))
        available? (and loaded? (az/field card available))
        chosen? (and available? (az/field card chosen) (ak/== selected i))
        playing? (and available? (az/field card chosen) (ak/== preview-node i) (playing-preview?))
        ^{:zig/type :u32} border (if chosen? 0x356cd5 0xdce3ed)
        ^{:zig/type :u32} background (if chosen? 0xeaf1ff 0xffffff)]
    (rounded-rect! x y width 54.0 6.0 border)
    (rounded-rect! (+ x 1.0) (+ y 1.0) (- width 2.0) 52.0 5.0 background)
    (if available?
      (do
        (draw-take-card-wave! card (+ x 43.0) (+ y 6.0) (- width 102.0))
        (seconds! (az/field card seconds) (- (+ x width) 48.0) (+ y 8.0) 0.21)
        (label! (az/slice (az/field card label) 0 (az/field card label-length))
                (+ x 10.0) (+ y 31.0) (- width 20.0) 0x26364a)
        (when (icon-button! (if playing? 1 0) "Audition this take" (+ x 6.0) (+ y 3.0) 32.0
                           (ak/! (busy?)) playing?)
          (request-take-card! index true))
        (when (and clicked (ak/! (busy?)) (inside? x y width 54.0))
          (request-take-card! index double-clicked)))
      (if (and loaded? (ak/! (az/field card missing))
               (< column 2) (> (az/field (node-id i) len) 0))
        (when (icon-button! 4 (if (ak/== column 0) "Record dry" "Record FX")
                           (+ x 6.0) (+ y 12.0) (- width 12.0) (ak/! (busy?)) false)
          ;; Explicit preparation, not capture. The user still presses Play / REC.
          (set-state! [selected i
                       record-track i
                       record-mode (if (ak/== column 0) 1 8)
                       record-scroll 0.0
                       seek-seconds 0.0])
          (select-workspace! 1))
        (label! (cond
                  (ak/! loaded?) "Loading..."
                  (az/field card missing) "Media unavailable"
                  (ak/== (az/field (node-id i) len) 0) "Text only"
                  :else "No favorite yet")
                (+ x 12.0) (+ y 18.0) (- width 24.0) 0x78869a)))))

(az/defn- draw-take-grid-row! :- :void [[slot :u32]]
  (let [i (+ track-offset slot)
        y (+ 169.0 (* 62.0 (ak/as :f32 (ak/floatFromInt slot))))]
    (when (< i scene/passage-entity-count)
      (let [data (ak/& (az/index scene/stories scene/active-story))
            node (az/index (az/field data nodes) i)]
        (draw-track-labels! i y (az/field node speaker) (> (az/field node id_len) 0) (ak/== selected i)))
      (when (and clicked (ak/! (busy?)) (inside? 16.0 y 220.0 54.0))
        (when (ak/!= selected i)
          (set! seek-seconds 0.0))
        (set-state! [selected i
                     focus-scroll 0.0
                     clicked false]))
      (dotimes [column 3]
        (draw-take-card! i slot (ak/intCast column) y)))))

(az/defn draw-takes-workspace! :- :void []
  (ui-text! "TAKES" 24.0 110.0 0.28 0x182637)
  (when (button! "<" 154.0 104.0 32.0)
    (set! track-offset (- track-offset (ak/min track-offset (visible-row-count)))))
  (when (button! ">" 194.0 104.0 32.0)
    (set! track-offset (ak/min (+ track-offset (visible-row-count))
                               (- (ak/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))))
  (label! "Select a card to edit. Triangle: audition. Double-click: play."
          250.0 110.0 (- (timeline-width) 16.0) 0x586777)
  (dotimes [column 3]
    (ui-text! (cond
                (ak/== column 0) "DRY / CLEAN VOICE"
                (ak/== column 1) "FX / PROCESSED"
                :else "FAVORITE")
              (+ (take-card-x (ak/intCast column)) 8.0) 145.0 0.21 0x586777))
  (dotimes [slot (visible-row-count)]
    (draw-take-grid-row! (ak/intCast slot)))
  (draw-take-editor!))

(az/defn meter-active? :- :bool [[input? :bool]]
  (if input?
    (or (recorder/input-check-active?)
        recorder/source-running
        (and recorder/running (< recorder/mode 3)))
    (or recorder/monitoring
        (and recorder/running (>= recorder/mode 2)))))

(az/defn meter-fraction :- :f32 [[active? :bool] [peak :f32]]
  ;; The held peak is diagnostic history, not a live signal. Never draw it as one.

  (if active?
    (ak/sqrt (ak/max 0.0 (ak/min 1.0 peak)))
    0.0))

(az/defn live-meter-fraction :- :f32 [[input? :bool]]
  (meter-fraction (meter-active? input?) (recorder/signal-peak input? false)))

(az/defn counting-in? :- :bool []
  (> countdown-until (glfw/glfwGetTime)))

(az/defn record-guidance :- [:slice-const :u8] [[phase :u8] [enabled? :bool] [armed? :bool]]
  (cond
    (ak/== phase 4)
    "Audio stopped. PCM retained; Stop retries saving."
    (ak/== phase 1)
    (if (counting-in?)
      "Count-in running. Stop to cancel."
      "Opening audio devices. Stop to cancel.")
    (ak/== phase 2)
    "Recording. Stop to save."
    (ak/== phase 3)
    "Capturing FX tail. Saving shortly."
    (ak/! armed?)
    "Select and arm a voiced passage."
    enabled?
    "Armed. Play to record."
    :else
    "REC off: enable REC to record."))

(az/defn record-script :- [:slice-const :u8] []
  (if (showing-capture-script?)
    (az/slice capture-script-text 0 capture-script-length)
    (scene/story-text selected)))

(az/defn armed-passage? :- :bool []
  (and (< record-track scene/passage-entity-count)
       (> (az/field (node-id record-track) len) 0)))

(az/defn record-workspace-guidance :- [:slice-const :u8]
  [[phase :u8] [voiced? :bool] [playing? :bool] [paused? :bool]]
  (cond
    (> phase 0)
    (record-guidance phase true true)

    (ak/! voiced?)
    "Text only. Select a voiced passage."

    playing?
    "Listening. Record starts a new take."

    paused?
    "Paused. Record starts a new take."

    :else
    "Record a new take for this passage."))

(az/defn- draw-record-target! :- :void []
  (if (showing-capture-script?)
    (do
      (ui-text! "CAPTURE TARGET" 266.0 178.0 0.19 0xc63535)
      (label! (record-script) 375.0 175.0 (- (timeline-width) 157.0) 0x586777))
    (when (< selected scene/passage-entity-count)
      (ui-text! "PASSAGE" 266.0 178.0 0.19 0x586777)
      (number! (+ selected 1) 327.0 177.0 0.20)
      (label! (scene/story-text selected)
              357.0 175.0 (- (timeline-width) 139.0) 0x586777))))

(az/defn- draw-record-passages! :- :void []
  (scene/rect! 16.0 104.0 220.0 (- window-height 144.0) 0xf2f4f8 0.0)
  (ui-text! "Passages" 28.0 114.0 0.30 0x182637)
  (when (button! "<" 154.0 110.0 32.0)
    (set! track-offset (- track-offset (ak/min track-offset (record-row-count)))))
  (when (button! ">" 194.0 110.0 32.0)
    (set! track-offset (ak/min (+ track-offset (record-row-count))
                               (- (ak/max (record-row-count) scene/passage-entity-count) (record-row-count)))))
  (dotimes [slot (record-row-count)]
    (let [i (+ track-offset (ak/as :u32 (ak/intCast slot)))
          y (+ 156.0 (* 54.0 (ak/as :f32 (ak/floatFromInt slot))))]
      (when (< i scene/passage-entity-count)
        (scene/rect! 16.0 y 220.0 50.0 (if (ak/== selected i)
                                         0xe0ebff
                                         0xf2f4f8) 0.0)
        (when (ak/== selected i)
          (scene/rect! 16.0 y 3.0 50.0 0x356cd5 0.0))
        (number! (+ i 1) 28.0 (+ y 4.0) 0.25)
        (label! (scene/story-text i) 51.0 (+ y 4.0) 174.0 0x26364a)
        (ui-text! (if (capture-passage? i)
                    "Recording target"
                    (passage-freshness-label i))
                  51.0 (+ y 25.0) 0.22 (if (capture-passage? i)
                                         0xc63535
                                         0x586777))
        (when (and clicked
                   (ak/! (busy?))
                   (inside? 16.0 y 220.0 50.0))
          (when (ak/!= selected i)
            (set! seek-seconds 0.0))
          (set-state! [selected i
                       record-scroll 0.0
                       focus-scroll 0.0
                       clicked false])))))
  (label! "F2: Edit / Record / Takes" 28.0 (bottom-y 682.0) 194.0 0x586777))

(az/defn- draw-record-script! :- :void []
  (scene/rect! 266.0 190.0 38.0 2.0 0xadc4e5 0.0)
  (ui-text! "DIALOGUE RECORDING" 266.0 114.0 0.25 0x356cd5)
  (when (icon-button! (if (recorder/input-check-active?) 2 0)
                       (if (recorder/input-check-active?) "Stop check" "Check input")
                       510.0 108.0 132.0 (ak/! (busy?)) false)
    (request! 39))
  (when (inside? 510.0 108.0 132.0 30.0)
    (hint! "Check input for 10 seconds. No recording, effects send or speaker playback."))
  (when (button! (if routing-visible
                   "Hide routing"
                   "Show routing") (content-x 724.0) 108.0 148.0)
    (show-routing! (ak/! routing-visible)))
  (when (icon-button! 4 "Record new take" 266.0 150.0 156.0
                       (and (ak/! (busy?)) (recordable-selection?)) false)
    (request-selected-recording!))
  (label! (if (effects-pass?)
            "Processing saved take. Stop to finish."
            (record-workspace-guidance (capture-phase-value)
                                     (recordable-selection?)
                                     (playing-preview?)
                                     preview-paused))
          438.0 154.0 (- (timeline-width) 212.0) 0x586777)
  (draw-record-target!)
  (set-state! [record-text-height (- (+ record-scroll
                                        (paragraph! (record-script) 266.0 (- 204.0 record-scroll)
                                                    (- (timeline-width) 48.0) 0.52 204.0 (+ 204.0 (record-pane-height)) 0x182637)) 204.0)
               record-scroll (ak/max 0.0 (ak/min record-scroll (ak/max 0.0 (- record-text-height (record-pane-height)))))])
  (when (> record-text-height (record-pane-height))
    (when (button! "Up" (content-x 724.0) (bottom-y 442.0) 70.0)
      (set! record-scroll (ak/max 0.0 (- record-scroll 100.0))))
    (when (button! "Down" (content-x 802.0) (bottom-y 442.0) 70.0)
      (set! record-scroll (ak/min (- record-text-height (record-pane-height)) (+ record-scroll 100.0))))))

(az/defn- draw-record-meters! :- :void []
  (rounded-rect! 254.0 (bottom-y 468.0) (- (timeline-width) 24.0) 122.0 8.0 0xf6f8fb)
  (ui-text! (if (effects-pass?) "SEND" "INPUT") 266.0 (bottom-y 479.0) 0.24 0x586777)
  (label! (az/slice input-meter-text 0 input-meter-length) 338.0 (bottom-y 479.0) (- (timeline-width) 118.0) 0x26364a)
  (scene/rect! 266.0 (bottom-y 510.0) (- (timeline-width) 48.0) 10.0 0xdce3ed 0.0)
  (scene/rect! 266.0 (bottom-y 510.0) (* (- (timeline-width) 48.0)
                                         (live-meter-fraction true)) 10.0 0x258060 0.0)
  (ui-text! "FX RETURN" 266.0 (bottom-y 537.0) 0.24 0x586777)
  (label! (az/slice return-meter-text 0 return-meter-length) 370.0 (bottom-y 537.0) (- (timeline-width) 150.0) 0x26364a)
  (scene/rect! 266.0 (bottom-y 566.0) (- (timeline-width) 48.0) 10.0 0xdce3ed 0.0)
  (scene/rect! 266.0 (bottom-y 566.0) (* (- (timeline-width) 48.0)
                                         (live-meter-fraction false)) 10.0 0x356cd5 0.0))

(az/defn- draw-record-audition! :- :void []
  (ui-text! (if (>= capture-phase 2)
              (if (capture-fx?) "LIVE FX RETURN" "LIVE INPUT")
              "TAKE AUDITION")
            254.0 (bottom-y 592.0) 0.19 0x586777)
  (waveform! (bottom-y 611.0))
  (when (and clicked
             (ak/! (busy?))
             (selected-waveform?)
             (inside? 242.0 (bottom-y 611.0) (timeline-width) 64.0))
    (position! (* take-seconds (ak/max 0.0 (ak/min 1.0 (/ (- (ak/as :f32 (ak/floatCast mouse-x)) 242.0) (timeline-width)))))))
  (let [active (and (selected-preview?) (playing-preview?))]
    (when (icon-button! (if active
                          1
                          0)
                        (if active
                          "Pause take"
                          (if (and (selected-preview?) preview-paused)
                            "Resume take"
                            "Listen to take"))
                        242.0 (bottom-y 685.0) 166.0
                        (and (ak/! (busy?)) (> (selected-take-seconds) 0.0)) active)
      (request! 35)))
  (when (enabled-button! "Previous take" 424.0 (bottom-y 685.0) 132.0 (and (ak/! (busy?)) (> (selected-take-seconds) 0.0))
                         "Stop recording and select a saved take first.")
    (request! 4))
  (when (enabled-button! "Next take" 566.0 (bottom-y 685.0) 118.0 (and (ak/! (busy?)) (> (selected-take-seconds) 0.0))
                         "Stop recording and select a saved take first.")
    (request! 5)))

(az/defn draw-record-workspace! :- :void []
  (rounded-rect! 16.0 105.0 (main-width) (- window-height 144.0) 9.0 0xe0e5ed)
  (rounded-rect! 16.0 104.0 (main-width) (- window-height 144.0) 9.0 0xffffff)
  (draw-record-passages!)
  (draw-record-script!)
  (draw-record-meters!)
  (draw-record-audition!))

(az/defn- begin-ui-frame! :- :void []
  (set! rendered-frames (+ rendered-frames 1))
  ;; The audio callback advances independently; sample once, not once per row.

  (set-state! [frame-cursor (cursor-seconds)
               capture-cursor (if (ak/== capture-phase 2)
                                (/ (ak/as :f32 (ak/floatFromInt (recorder/frames-recorded))) 48000.0)
                                0.0)])
  (glfw/glfwGetCursorPos studio-window (ak/& mouse-x) (ak/& mouse-y))
  (set-state! [clicked event-pressed
               double-clicked event-double])
  (when event-pressed
    (set-state! [mouse-x press-x
                 mouse-y press-y]))
  (set-state! [event-pressed false
               event-double false])
  (when test-click
    (set-state! [mouse-x test-x
                 mouse-y test-y
                 clicked true
                 test-click false]))
  (set! route-click (and (> route-menu 0) clicked))
  (when (> route-menu 0)
    (set! clicked false))
  (divider-input!)
  (set! hint-length 0)
  (glfw/glfwSetCursor studio-window ak/null)
  (when (and clicked (ak/! (inside? 28.0 (bottom-y 621.0) 192.0 28.0)))
    (set! name-focus false))
  (when (>= selected scene/passage-entity-count)
    (set-state! [selected 0
                 focus-scroll 0.0]))
  (set! track-offset (ak/min track-offset (- (ak/max 1 scene/passage-entity-count) 1))))

(az/defn- draw-workspace-header! :- :void []
  (scene/rect! 0.0 0.0 window-width window-height 0xeff2f6 0.0)
  (scene/rect! 0.0 0.0 window-width 94.0 0xffffff 0.0)
  (scene/rect! 0.0 42.0 window-width 1.0 0xe7ebf0 0.0)
  (scene/rect! 0.0 93.0 window-width 1.0 0xdce2ea 0.0)
  (ui-text! "La Professeure / Studio" 16.0 11.0 0.30 0x182637)
  (when (button! "Edit" 246.0 9.0 48.0)
    (set-workspace-mode! false))
  (when (button! "Record" 302.0 9.0 68.0)
    (set-workspace-mode! true))
  (when (button! "Takes" 378.0 9.0 62.0)
    (select-workspace! 2))
  (scene/rect! (cond
                 (ak/== workspace-mode 0) 246.0
                 (ak/== workspace-mode 1) 302.0
                 :else 378.0) 36.0
               (cond
                 (ak/== workspace-mode 0) 48.0
                 (ak/== workspace-mode 1) 68.0
                 :else 62.0) 2.0 0x356cd5 0.0)
  (if mix-mode
    (do
      (when (button! (if (az/field (mixer/loop-state) enabled)
                       "Loop: on"
                       "Loop: off") (right-x 602.0) 9.0 94.0)
        (request! 31))
      (when (button! "A" (right-x 702.0) 9.0 34.0)
        (request! 32))
      (when (button! "B" (right-x 742.0) 9.0 34.0)
        (request! 33))
      (when (inside? (right-x 602.0) 9.0 94.0 28.0)
        (hint! "Loop the mix between A and B. F1: game window."))
      (when (inside? (right-x 702.0) 9.0 34.0 28.0)
        (hint! "A: set loop start at the cursor."))
      (when (inside? (right-x 742.0) 9.0 34.0 28.0)
        (hint! "B: set loop end at the cursor.")))
    (do
      (when (button! (if audition-boost
                       "Audition boost"
                       "Original gain") (right-x 602.0) 9.0 174.0)
        (when (ak/! (busy?))
          (set! audition-boost (ak/! audition-boost))
          (update-audition-gain!)))
      (when (inside? (right-x 602.0) 9.0 174.0 28.0)
        (hint! "Boost quiet takes for listening only. Files and effects stay unchanged. F1: game."))))
  (when (button! (if mix-mode
                   "Take mode"
                   "Play mix") 448.0 9.0 136.0)
    (request! 30))
  (when (button! "Undo" (right-x 788.0) 9.0 140.0)
    (request! 27))
  (when (button! "Redo" (right-x 940.0) 9.0 140.0)
    (request! 28)))

(az/defn- draw-transport! :- :void []
  (let [playing? (playing-preview?)
        focused-record? (ak/!= workspace-mode 0)
        record-enabled? (and (ak/! focused-record?) (ak/== record-enabled 1))
        label (cond
                playing? "PAUSE"
                (paused-preview?) "RESUME"
                record-enabled? "PLAY / REC"
                :else "PLAY")
        enabled? (and (ak/! (busy?))
                      (or record-enabled?
                          mix-mode
                          (> (selected-take-seconds) 0.0)
                          (paused-preview?)
                          playing?))
        active? playing?]
    (when (icon-button! (if playing? 1 0) label
                       16.0 51.0 130.0 enabled? active?)
      (request! (transport-action))))
  (when (icon-button! 2 "STOP" 154.0 51.0 86.0 true false)
    (ak/atomicStore :u32 (ak/& pending) 2 :.release))
  (when (icon-button! 3 "Back to start (Home)" 248.0 51.0 42.0 (ak/! (busy?)) false)
    (position! 0.0))
  (let [focused? (ak/!= workspace-mode 0)]
    (when (icon-button! 4 (if focused? "RECORD" "REC") 301.0 51.0 100.0
                         (and (ak/! (busy?)) (or (ak/! focused?) (recordable-selection?)))
                         (if focused? (> (capture-phase-value) 0) (ak/== record-enabled 1)))
      (if focused?
        (request-selected-recording!)
        (request! 34))))
  (when (inside? 301.0 51.0 100.0 28.0)
    (hint! (if (ak/!= workspace-mode 0)
              "Record a new take for the selected voiced passage. Stop to save."
              "Enable REC, then Play. Arm a track with its red circle.")))
  (when (button! (record-route-label) 411.0 51.0 87.0)
    (when (ak/! (busy?))
      (set! record-mode (if (ak/== record-mode 8)
                          1
                          8))))
  (draw-transport-clock!)
  (draw-recording-options!))

(az/defn transport-state-label :- [:slice-const :u8]
  [[phase :u8]
   [working? :bool]
   [playing? :bool]
   [paused? :bool]
   [record-enabled? :bool]]
  (cond
    (ak/== phase 4) "AUDIO STOPPED"
    (ak/== phase 3) "FX TAIL"
    (ak/== phase 2) "RECORDING"
    (ak/== phase 1) (if (counting-in?) "COUNT-IN" "PREPARING")
    working? "WORKING"
    playing? "PLAYING"
    paused? "PAUSED"
    record-enabled? "REC ENABLED"
    :else "STOPPED"))

(az/defn- draw-transport-clock! :- :void []
  (let [label (transport-state-label (capture-phase-value)
                                     (busy?)
                                     (playing-preview?)
                                     preview-paused
                                     (and (ak/== workspace-mode 0) (ak/== record-enabled 1)))
        seconds (cond
                  (or (ak/== capture-phase 2) (ak/== capture-phase 4))
                  capture-cursor
                  (ak/== capture-phase 1)
                  (ak/as :f32 (ak/floatCast (ak/max 0.0 (- countdown-until (glfw/glfwGetTime)))))
                  :else
                  frame-cursor)]
    (ui-text! label 506.0 44.0 0.20 0x356cd5)
    (seconds! seconds 506.0 65.0 0.30)
    (ui-text! "s" 562.0 67.0 0.23 0x586777)))

(az/defn- draw-recording-options! :- :void []
  (when (enabled-button! "Count-in" (right-x 602.0) 52.0 112.0 (ak/! (busy?)) "Stop before changing count-in.")
    (set! countdown-seconds (if (ak/== countdown-seconds 0)
                              3
                              0)))
  (number! countdown-seconds (right-x 684.0) 56.0 0.24)
  (let [tail-label (cond
                     (ak/== tail-seconds 0) "Tail 0 s"
                     (ak/== tail-seconds 1) "Tail 1 s"
                     (ak/== tail-seconds 3) "Tail 3 s"
                     :else "Tail 5 s")]
    (when (button! tail-label (right-x 724.0) 52.0 78.0)
      (when (ak/! (busy?))
        (set! tail-seconds
              (cond
                (ak/== tail-seconds 0) 1
                (ak/== tail-seconds 1) 3
                (ak/== tail-seconds 3) 5
                :else 0)))))
  (when (button! (if (ak/== compensate 1)
                   "Align: on"
                   "Align: off") (right-x 812.0) 52.0 96.0)
    (when (ak/! (busy?))
      (set! compensate (- 1 compensate))))
  (when (enabled-button! "Publish to game" (right-x 917.0) 52.0 168.0
                         (take-editable?) "Select a loaded take before publishing.")
    (request! 7)))

(az/defn monitor-level :- :u32 []
  (ak/min 50 (ak/atomicLoad :u32 (ak/& recorder/monitor-gain) :.acquire)))

(az/defn set-monitor-level! :- :void [[percent :u32]]
  (ak/atomicStore :u32 (ak/& recorder/monitor-gain) (ak/min 50 percent) :.release))

(az/defn monitor-level-at :- :u32 [[pointer-x :f64]]
  (let [fraction (/ (- pointer-x (right-x 914.0)) 156.0)
        bounded (ak/max 0.0 (ak/min 1.0 fraction))]
    (ak/intFromFloat (+ 0.5 (* bounded 50.0)))))

(az/defn update-monitor-level! :- :void []
  (when (or (ak/! routing-visible) (> route-menu 0))
    (set! monitor-level-drag false)
    (ak/return))
  (when (and clicked (inside? (right-x 906.0) 497.0 170.0 22.0))
    (set! monitor-level-drag true))
  (when monitor-level-drag
    (set-monitor-level! (monitor-level-at mouse-x))
    (set! clicked false)
    (when (ak/! mouse-down)
      (set! monitor-level-drag false))))

(az/defn- draw-monitor-level! :- :void []
  (let [gain (monitor-level)
        thumb-x (+ (right-x 914.0) (* 3.12 (ak/as :f32 (ak/floatFromInt gain))))
        hover? (inside? (right-x 906.0) 497.0 170.0 22.0)]
    (ui-text! "Monitor level" (right-x 908.0) 482.0 0.21 0x586777)
    (number! gain (right-x 1034.0) 482.0 0.21)
    (ui-text! "%" (right-x 1059.0) 482.0 0.21 0x586777)
    (rounded-rect! (right-x 914.0) 506.0 156.0 4.0 2.0 0xdce3ed)
    (when (> gain 0)
      (rounded-rect! (right-x 914.0) 506.0 (- thumb-x (right-x 914.0)) 4.0 2.0 0x356cd5))
    (rounded-rect! (- thumb-x 5.0) 501.0 10.0 14.0 3.0
                   (if (or hover? monitor-level-drag) 0x235ac0 0x356cd5))
    (when (or hover? monitor-level-drag)
      (hint! "Drag to set live headphone monitoring (0-50%). Does not enable monitoring or change takes or playback gain."))))

(az/defvar routing-tools-visible :bool false)

(az/defn show-routing-tools! :- :void [[visible :bool]]
  (set-state! [routing-tools-visible visible
               clicked false]))

(az/defn routing-controls-available? :- :bool []
  (and (ak/! (busy?)) (ak/! (recorder/input-check-active?))))

(az/defn- draw-routing-tools! :- :void []
  (let [idle? (ak/! (busy?))
        route-ready? (routing-controls-available?)]
    (rounded-rect! (right-x 896.0) 524.0 190.0
                   (if routing-tools-visible 194.0 40.0) 8.0 0xf8fafd)
    (when (button! (if routing-tools-visible "Routing tools -" "Routing tools +")
                   (right-x 906.0) 530.0 170.0)
      (show-routing-tools! (ak/! routing-tools-visible)))
    (when (inside? (right-x 906.0) 530.0 170.0 28.0)
      (hint! "Show or hide routing profiles, reconnect, offline FX processing and interrupted-take recovery."))
    (when routing-tools-visible
      (when (enabled-button! "Save profile" (right-x 906.0) 562.0 170.0 idle?
                             "Stop recording before saving routing settings.")
        (request! 20))
      (when (enabled-button! "Next profile" (right-x 906.0) 594.0 170.0 route-ready?
                             "Stop recording or the input check before changing routing profiles.")
        (request! 21))
      (when (enabled-button! "Reconnect" (right-x 906.0) 626.0 170.0 route-ready?
                             "Stop recording or the input check before reconnecting devices.")
        (request! 22))
      (when (enabled-button! "Process take FX" (right-x 906.0) 658.0 170.0
                             (and idle? (> (selected-take-seconds) 0.0))
                             "Stop recording and select a saved take to process through the FX route.")
        (request! 3))
      (when (enabled-button! "Recover takes" (right-x 906.0) 690.0 170.0 idle?
                             "Stop recording before recovering interrupted takes.")
        (request! 14)))))

(az/defn- draw-routing-panel! :- :void []
  ;; Explicit external routing. No dummy device or effect controls.

  (update-monitor-level!)
  (when routing-visible
    (when (inside? (right-x 908.0) 336.0 168.0 30.0)
      (hint! "FX route: input -> send 1/2 -> Bitwig -> return 3/4. Signal meters check audio, not the effect itself."))
    (rounded-rect! (right-x 896.0) 140.0 190.0 90.0 8.0 0xf8fafd)
    (rounded-rect! (right-x 896.0) 234.0 190.0 138.0 8.0 0xf8fafd)
    (rounded-rect! (right-x 896.0) 381.0 190.0 138.0 8.0 0xf8fafd)
    (when (enabled-button! "Mic / input..." (right-x 906.0) 148.0 170.0
                            (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 1))
    (label! (recorder/device-name true microphone) (right-x 908.0) 180.0 166.0 0x182637)
    (label! (az/slice input-meter-text 0 input-meter-length) (right-x 908.0) 204.0 168.0 0x586777)
    (scene/rect! (right-x 908.0) 224.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 224.0 (* 168.0 (live-meter-fraction true)) 5.0 0x356cd5 0.0)
    (when (enabled-button! "Send 1/2 > Bitwig..." (right-x 906.0) 234.0 170.0
                            (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 2))
    (label! (recorder/device-name false effects-output) (right-x 908.0) 266.0 166.0 0x586777)
    (when (enabled-button! "FX return 3/4..." (right-x 906.0) 289.0 170.0
                            (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 3))
    (label! (recorder/device-name true return-input) (right-x 908.0) 316.0 166.0 0x586777)
    (label! (az/slice return-meter-text 0 return-meter-length) (right-x 908.0) 338.0 168.0 0x586777)
    (scene/rect! (right-x 908.0) 360.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 360.0 (* 168.0 (live-meter-fraction false)) 5.0 0x356cd5 0.0)
    (when (enabled-button! "Listening output..." (right-x 906.0) 386.0 170.0
                            (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 4))
    (label! (recorder/device-name false headphones) (right-x 908.0) 419.0 166.0 0x586777)
    (scene/rect! (right-x 908.0) 440.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 440.0 (* 168.0 (ak/min 1.0 (* 0.00001 (ak/as :f32 (ak/floatFromInt (ak/atomicLoad :u32 (ak/& playback-level) :.acquire)))))) 5.0 0x356cd5 0.0)
    (when (enabled-button! (if (ak/== monitor-enabled 0)
                     "Monitoring: off"
                     "Monitoring: on") (right-x 906.0) 450.0 170.0
                            (routing-controls-available?)
                            "Stop recording or the input check before enabling or disabling monitoring.")
      (request! 23))
    (when (inside? (right-x 906.0) 450.0 170.0 28.0)
      (hint! (if (routing-controls-available?)
                "Live monitoring requires headphones. This does not mute take playback."
                "Stop recording or the input check before enabling or disabling monitoring.")))
    (draw-monitor-level!)
    (draw-routing-tools!)))

(az/defn- draw-status-bar! :- :void []
  (label! (if (selected-waveform?)
            (az/slice details 0 details-length)
            "Loading selected take...")
          16.0 (bottom-y 729.0) 520.0 0x356cd5)
  (label! (if (> hint-length 0)
            (az/slice hint-text 0 hint-length)
            (az/slice status-text 0 status-length)) 550.0 (bottom-y 729.0) (right-x 535.0) 0x26364a)
  (when (> alert-length 0)
    (scene/rect! 0.0 (bottom-y 722.0) window-width 38.0 0xffe7e1 0.0)
    (label! (az/slice alert-text 0 alert-length) 16.0 (bottom-y 730.0) (right-x 1015.0) 0x962f20)
    (when (button! "X" (right-x 1050.0) (bottom-y 726.0) 34.0)
      (set! alert-length 0))))

(az/defn- draw-device-menu! :- :void []
  (when (> route-menu 0)
    (set! clicked route-click)
    (when (and clicked (ak/! (inside? (right-x 558.0) 137.0 522.0 371.0)))
      (set-state! [route-menu 0
                   clicked false])))
  (when (> route-menu 0)
    (let [capture (or (ak/== route-menu 1) (ak/== route-menu 3))
          total (route-total)
          active (route-selected)]
      (scene/rect! (right-x 558.0) 137.0 522.0 371.0 0xffffff 0.0)
      (ui-text! (if (ak/== route-menu 1)
                  "SELECT MICROPHONE / INPUT"
                  (if (ak/== route-menu 2)
                    "SEND TO BITWIG (1/2)"
                    (if (ak/== route-menu 3)
                      "PROCESSED RETURN (3/4)"
                      "OUTPUT: TAKES / MIX / HEADPHONES"))) (right-x 574.0) 146.0 0.30 0x182637)
      (when (button! "X" (right-x 1030.0) 144.0 34.0)
        (set! route-menu 0))
      (dotimes [row 8]
        (let [i (+ route-offset (ak/as :u32 (ak/intCast row)))
              y (+ 187.0 (* 32.0 (ak/as :f32 (ak/floatFromInt row))))]
          (when (< i total)
            (let [hover (inside? (right-x 574.0) y 490.0 28.0)]
              (scene/rect! (right-x 574.0) y 490.0 28.0 (if (ak/== i active)
                                                          0xdce8ff
                                                          (if hover
                                                            0xe0eaff
                                                            0xeef2f7)) 0.0)
              (when (ak/== i route-focus)
                (scene/rect! (right-x 574.0) y 3.0 28.0 0x356cd5 0.0))
              (label! (recorder/device-name capture i) (right-x 584.0) (+ y 4.0) 366.0 0x182637)
              (when (ak/== i active)
                (ui-text! "Selected" (right-x 983.0) (+ y 4.0) 0.22 0x235ac0))
              (when hover
                (glfw/glfwSetCursor studio-window hand-cursor))
              (when (and clicked hover)
                (route-select! i))))))
      (when (ak/== total 0)
        (ui-text! "No devices found. Close and reconnect." (right-x 584.0) 197.0 0.24 0x356cd5))
      (ui-text! "Arrows + Enter to select. Escape / outside click to close." (right-x 574.0) 450.0 0.20 0x586777)
      (if (> route-offset 0)
        (when (button! "Previous" (right-x 574.0) 477.0 130.0)
          (set-state! [route-offset (- route-offset (ak/min route-offset 8))
                       route-focus route-offset]))
        (ui-text! "Previous" (right-x 584.0) 482.0 0.24 0x78869a))
      (if (< (+ route-offset 8) total)
        (when (button! "Next" (right-x 716.0) 477.0 130.0)
          (set-state! [route-offset (+ route-offset 8)
                       route-focus route-offset]))
        (ui-text! "Next" (right-x 726.0) 482.0 0.24 0x78869a)))))

(az/defn draw! {:attrs #{:export}} :- :void []
  (begin-ui-frame!)
  (draw-workspace-header!)
  (draw-transport!)
  (when (ak/== workspace-mode 1)
    (draw-record-workspace!))
  (when (ak/== workspace-mode 0)
    (draw-edit-workspace!))
  (when (ak/== workspace-mode 2)
    (draw-takes-workspace!))
  (draw-routing-panel!)
  (draw-status-bar!)
  ;; Modal input is consumed last, after suppressing underlying clicks.

  (draw-device-menu!))

(az/defstruct FrameTiming {:layout :extern}
  [[interval-ms :f32]
   [build-ms :f32]
   [render-ms :f32]])

(az/defvar frame-timings [:array 240 FrameTiming] (mem/zeroes (az/type [:array 240 FrameTiming])))
(az/defvar frame-timing-count :u32 0)
(az/defvar frame-timing-index :u32 0)
(az/defvar frame-previous-start :f64 0.0)
(az/defvar frame-build-ms :f32 0.0)

(az/defn reset-frame-timings! :- :void []
  (set-state! [frame-timing-count 0
               frame-timing-index 0
               frame-previous-start 0.0
               frame-build-ms 0.0]))

(az/defn record-frame-timing! :- :void [[started :f64] [finished :f64]]
  (when (> frame-previous-start 0.0)
    (set! (az/index frame-timings frame-timing-index)
          (FrameTiming
            {:interval-ms (ak/floatCast (* 1000.0 (ak/max 0.0 (- started frame-previous-start))))
             :build-ms frame-build-ms
             :render-ms (ak/floatCast (* 1000.0 (ak/max 0.0 (- finished started))))}))
    (set-state! [frame-timing-index (mod (+ frame-timing-index 1) 240)
                 frame-timing-count (ak/min 240 (+ frame-timing-count 1))]))
  (set! frame-previous-start started))

(az/defn build-frame {:zig/qualifiers "callconv(.c)"} :- :u32
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (update-layout!)
  (set! framebuffer-scale (/ (ak/as :f32 (ak/floatFromInt width)) window-width))
  (set! _ height)
  (let [started (glfw/glfwGetTime)
        old-vertices scene/vertices
        old-count scene/vertex-count
        old-width scene/canvas-width
        old-height scene/canvas-height]
    (ak/defer (do
                (set-state! [scene/vertices old-vertices
                             scene/vertex-count old-count
                             scene/canvas-width old-width
                             scene/canvas-height old-height])))
    (set-state! [scene/vertices output
                 scene/vertex-count 0
                 scene/canvas-width window-width
                 scene/canvas-height window-height])
    (draw!)
    (set! frame-build-ms (ak/floatCast (* 1000.0 (- (glfw/glfwGetTime) started))))
    scene/vertex-count))

;; Native game callback: called only by the render loop. REPL callers use the
;; host focus-window! below so AppKit is never entered from an nREPL thread.
(az/defn focus-window-native! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when (ak/!= studio-window ak/null)
    (glfw/glfwShowWindow studio-window)
    (glfw/glfwFocusWindow studio-window)))

(az/defn tick-window! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when (or (ak/! attached) (ak/== studio-window ak/null))
    (set! frame-previous-start 0.0)
    (ak/return))
  ;; Window close hides without discarding a recording. F1 shows it again;
  ;; close! explicitly releases the tool's resources, never the game's.

  (when (ak/!= (glfw/glfwWindowShouldClose studio-window) 0)
    (ak/atomicStore :u32 (ak/& pending) 2 :.release)
    (glfw/glfwSetWindowShouldClose studio-window 0)
    (glfw/glfwHideWindow studio-window))
  ;; Keep game audio out of a take even when switching to Bitwig during capture.

  (suppress-game-audio! (or (busy?)
                            (ak/!= capture-phase 0)
                            (and (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
                                 (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)
                                 (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_FOCUSED) 0))))
  (when (or (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
            (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0))
    (set! frame-previous-start 0.0)
    (ak/return))
  (gpu/swap-context! (ak/& renderer))
  (ak/defer (gpu/swap-context! (ak/& renderer)))
  ;; The existing game watcher publishes first. Refresh this window once for
  ;; that revision, retaining its old pipeline on failure (no per-frame retries).

  (when (ak/!= observed-shaders gpu/shader-publications)
    (when (ak/! (gpu/reload-shaders!))
      (status! "Studio shader rejected; previous version retained."))
    (set! observed-shaders gpu/shader-publications))
  (let [started (glfw/glfwGetTime)]
    (set! frame-build-ms 0.0)
    (when (gpu/render! (ak/& build-frame))
      (record-frame-timing! started (glfw/glfwGetTime)))))

(az/defn reload-assets! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when attached
    (gpu/swap-context! (ak/& renderer))
    (ak/defer (gpu/swap-context! (ak/& renderer)))
    (gpu/renderer-wait-idle!)
    (gpu/load-atlas!)))

;; GLFW geometry is in screen points, never Retina framebuffer pixels.
(az/defstruct WindowBounds {:layout :extern}
              [[x :i32] [y :i32] [width :i32] [height :i32]
               [left :i32] [top :i32] [right :i32] [bottom :i32] [normal :i32]])

(az/defn window-bounds :- WindowBounds []
  (let [^{:var WindowBounds} bounds (mem/zeroes (az/type WindowBounds))]
    (when (ak/!= studio-window ak/null)
      (glfw/glfwGetWindowPos studio-window (ak/& (az/field bounds x)) (ak/& (az/field bounds y)))
      (glfw/glfwGetWindowSize studio-window (ak/& (az/field bounds width)) (ak/& (az/field bounds height)))
      (glfw/glfwGetWindowFrameSize studio-window (ak/& (az/field bounds left)) (ak/& (az/field bounds top))
                                   (ak/& (az/field bounds right)) (ak/& (az/field bounds bottom)))
      (set! (az/field bounds normal)
            (if (and (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)
                     (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_MAXIMIZED) 0))
              1
              0)))
    bounds))

(az/defn monitor-count :- :u32 []
  (let [^{:var :c_int} count 0]
    (set! _ (glfw/glfwGetMonitors (ak/& count)))
    (ak/intCast (ak/max 0 count))))

(az/defn monitor-bounds :- WindowBounds [[index :u32]]
  (let [^{:var WindowBounds} bounds (mem/zeroes (az/type WindowBounds))
        ^{:var :c_int} count 0
        monitors (glfw/glfwGetMonitors (ak/& count))]
    (when (and (ak/!= monitors ak/null)
               (< index (ak/as :u32 (ak/intCast (ak/max 0 count)))))
      (glfw/glfwGetMonitorWorkarea (az/index monitors (ak/intCast index))
                                   (ak/& (az/field bounds x)) (ak/& (az/field bounds y))
                                   (ak/& (az/field bounds width)) (ak/& (az/field bounds height))))
    bounds))

(az/defn apply-window-bounds! :- :void [[x :i32] [y :i32] [width :i32] [height :i32]]
  (when (ak/!= studio-window ak/null)
    (glfw/glfwSetWindowSize studio-window width height)
    (glfw/glfwSetWindowPos studio-window x y)))

(az/defn window-size-limits! :- :void []
  (when (ak/!= studio-window ak/null)
    (glfw/glfwSetWindowSizeLimits studio-window 1100 760 glfw/GLFW_DONT_CARE glfw/GLFW_DONT_CARE)))

(az/defn enable-window-resizing! :- :void []
  (when (ak/!= studio-window ak/null)
    ;; GLFW skips native size-limit installation while a window is non-resizable.
    ;; Enable first, then apply limits to already-open development windows too.

    (glfw/glfwSetWindowAttrib studio-window glfw/GLFW_RESIZABLE glfw/GLFW_TRUE)
    (window-size-limits!)))

(az/defn window-resizable? :- :bool []
  (and (ak/!= studio-window ak/null)
       (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_RESIZABLE) 0)))

(az/defn attach! :- :void []
  (when (ak/== studio-window ak/null)
    (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
    (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_TRUE)
    (set! studio-window (glfw/glfwCreateWindow 1100 760 "La Professeure | Studio" ak/null ak/null))
    (when (ak/== studio-window ak/null)
      (ak/return))
    (window-size-limits!)
    (gpu/swap-context! (ak/& renderer))
    (set! _ (gpu/initialize-renderer! studio-window))
    (set! gpu/lighting 0.0)
    (gpu/swap-context! (ak/& renderer))
    (set! observed-shaders gpu/shader-publications))
  (set-state! [attached true
               scene/development-tick (ak/& tick-window!)
               scene/development-shutdown (ak/& detach!)
               scene/development-assets (ak/& reload-assets!)
               scene/development-focus (ak/& focus-window-native!)])
  (glfw/glfwShowWindow studio-window)
  (glfw/glfwFocusWindow studio-window)
  (when (ak/! callbacks-installed)
    (set-state! [previous-char (glfw/glfwSetCharCallback studio-window (ak/& typed!))
                 previous-scroll (glfw/glfwSetScrollCallback studio-window (ak/& scrolled!))
                 previous-key (glfw/glfwSetKeyCallback studio-window (ak/& key-event!))
                 previous-mouse (glfw/glfwSetMouseButtonCallback studio-window (ak/& mouse-event!))
                 hand-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HAND_CURSOR)
                 resize-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HRESIZE_CURSOR)
                 text-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_IBEAM_CURSOR)
                 callbacks-installed true]))
  (set-state! [event-pressed false
               mouse-down false
               trim-drag 0])
  (status! "Select a passage, then a microphone. Takes stay local."))

(az/defn detach! {:zig/qualifiers "callconv(.c)"} :- :void []
  (close-playback!)
  (mixer/close!)
  (suppress-game-audio! false)
  (when callbacks-installed
    (set! _ (glfw/glfwSetCharCallback studio-window previous-char))
    (set! _ (glfw/glfwSetScrollCallback studio-window previous-scroll))
    (set! _ (glfw/glfwSetKeyCallback studio-window previous-key))
    (set! _ (glfw/glfwSetMouseButtonCallback studio-window previous-mouse))
    (glfw/glfwSetCursor studio-window ak/null)
    (glfw/glfwDestroyCursor hand-cursor)
    (glfw/glfwDestroyCursor resize-cursor)
    (glfw/glfwDestroyCursor text-cursor)
    (when (ak/!= vertical-cursor ak/null)
      (glfw/glfwDestroyCursor vertical-cursor)
      (set! vertical-cursor ak/null))
    (set-state! [hand-cursor ak/null
                 resize-cursor ak/null
                 text-cursor ak/null
                 callbacks-installed false]))
  (when (ak/!= studio-window ak/null)
    (gpu/swap-context! (ak/& renderer))
    (gpu/shutdown-renderer!)
    (gpu/swap-context! (ak/& renderer))
    (glfw/glfwDestroyWindow studio-window)
    (set! studio-window ak/null))
  (set-state! [scene/development-tick ak/null
               scene/development-shutdown ak/null
               scene/development-assets ak/null
               scene/development-focus ak/null
               attached false]))

(defonce worker (atom nil))
(defonce worker-health (atom {:state :closed :failures 0}))

(defn worker-status
  "Nonblocking JVM health, including when a bad native edit prevents query."
  []
  (let [current @worker
        alive? (boolean (and current (not (future-done? current))))]
    (cond-> (assoc @worker-health :alive? alive?)
      (nil? current) (assoc :state :closed)
      (and current (not alive?)) (assoc :state :stopped))))

(defonce session (atom nil))

(defonce takes (atom {}))

(defonce project (atom nil))

(defonce mix-sources (atom []))

(defonce armed (atom nil))
(defonce input-check (atom nil))

(declare ^:private render!)

(defn- stop-input-check! []
  (recorder/stop-input-check!)
  (reset! input-check nil))

(defn- check-input! [enabled?]
  (if-not enabled?
    (stop-input-check!)
    (when-not @input-check
      (when-not (recorder/initialize!)
        (throw (ex-info "Audio inputs unavailable. Reconnect before checking input."
                        {:code :audio-unavailable})))
      (let [{:keys [source fx?]} (render! #(hash-map :source (az/value microphone)
                                                   :fx? (= 8 (az/value record-mode))))]
        (when-not (recorder/start-input-check! source fx?)
          (throw (ex-info "Cannot check input while its device is unavailable or in use."
                          {:code :input-unavailable})))
        (reset! input-check {:source source
                            :fx? fx?
                            :deadline-ns (+ (System/nanoTime) 10000000000)})))))

(defn- expire-input-check! []
  (when-let [{:keys [deadline-ns]} @input-check]
    (when (>= (System/nanoTime) deadline-ns)
      (stop-input-check!))))

(defonce comparison (atom nil))

(defonce display-cache (atom nil))

(defonce waveform-cache (atom {}))

(defonce clip-display-cache (atom nil))
(defonce take-grid-cache (atom nil))

(defonce presets (atom {}))

(defonce active-preset (atom nil))

(defonce command-queue (java.util.concurrent.ArrayBlockingQueue. 64))

(defonce request-ledger (atom {}))

(defonce completed-requests (atom []))

(defonce event-log (atom {:sequence 0 :events []}))

(defonce extensions (atom {}))

(def ^:dynamic *command-worker?* false)

(def presets-file (io/file "build/recording/routing.edn"))

(def index-file (io/file "build/recording/takes.edn"))

(def project-file (io/file "build/recording/project.edn"))

(def window-file (io/file "build/studio-window.edn"))

(defonce window-save-state (atom nil))

(defn- atomic-write! [destination write!]
  (io/make-parents destination)
  (let [target (.toPath (.getCanonicalFile (io/file destination)))
        temporary (Files/createTempFile (.getParent target) ".publish-" ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (write! (.toFile temporary))
      (Files/move temporary target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists temporary)))))

(defn- change-takes! [label change & [barrier?]]
  (let [next (files/commit-project! project project-file label change barrier?)]
    (reset! takes (:takes next))
    next))

(defn- remember!
  ([id kind path]
   (remember! id kind path
              (when (= id (:id @session))
                (select-keys @session [:dialogue-hash :interrupted?]))))
  ([id kind path provenance]
   (change-takes! "Add take"
     #(update % id
        (fn [entry]
          (-> (or entry {})
              (assoc kind path :selected path)
              (update :history (fnil conj [])
                      (merge (select-keys provenance [:dialogue-hash :interrupted?])
                             {:kind kind :path path}))))))))

(defn- native-string [value]
  (try (String. (byte-array (map unchecked-byte (az/value value))) "UTF-8")
       (finally (az/close! value))))

(defn- render! [f]
  (let [result (deref (core/on-render! f) 30000 ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info "Render thread timeout" {})))
    (when (:error result)
      (throw (:error result)))
    (:value result)))

(defn focus-window!
  "Show/focus the studio from the JVM through the render thread."
  []
  (render! #(focus-window-native!)))

(defn dialogue-fingerprint [speaker text]
  (dialogue/digest (pr-str [speaker text])))

(defn recording-freshness
  "The selected take's script match, not a claim about recording quality or publication."
  [fingerprint entry]
  (cond
    (nil? entry) :needs-recording
    (:interrupted? entry) :interrupted
    (nil? (:dialogue-hash entry)) :unverified
    (not= fingerprint (:dialogue-hash entry)) :changed
    :else :recorded))

(defn- passage-data [index]
  ;; Call inside one render task so identity, revision and text are coherent.
  {:index index
   :id (native-string (node-id index))
   :revision (node-revision index)
   :speaker (node-speaker index)
   :text (native-string (scene/story-text index))})

(defn- capture-script-snapshot! [expected-id]
  (render!
    #(let [{:keys [id speaker text] :as passage} (passage-data (az/value selected))]
       (when-not (= id expected-id)
         (throw (ex-info "Dialogue changed before recording. Select the passage again."
                         {:code :stale-dialogue})))
       (when-not (capture-script! id text)
         (throw (ex-info "Recording script exceeds the supported native capacity."
                         {:code :script-capacity})))
       (assoc passage :dialogue-hash (dialogue-fingerprint speaker text)))))

(defn- take-provenance [id path]
  (select-keys (some #(when (= path (:path %)) %) (get-in @takes [id :history]))
               [:dialogue-hash :interrupted?]))

(defonce dialogue-display (atom []))
(defonce dialogue-display-key (atom nil))

(defn- refresh-dialogue-status! []
  ;; Reading full strings through the native boundary is substantial work. The
  ;; watcher changes :at after publication; native slot/count also detect direct
  ;; story replacement. Meter ticks alone must not reread/hash the whole script.
  (let [take-state @takes
        key [(:dialogue @core/status)
             (render! #(vector (az/value scene/active-story)
                               (az/value scene/passage-entity-count)
                               (az/value track-offset)))
             take-state]]
    (when (not= key @dialogue-display-key)
      (let [rows (render!
                   #(let [offset (az/value track-offset)
                          end (min (az/value scene/passage-entity-count) (+ offset 32))]
                      (mapv passage-data (range offset end))))
            statuses
            (mapv
              (fn [{:keys [id speaker text] :as row}]
                (let [entry (get take-state id)
                      selected (some #(when (= (:selected entry) (:path %)) %)
                                     (:history entry))]
                  (assoc row :recording-status
                         (when (seq id)
                           (recording-freshness (dialogue-fingerprint speaker text) selected)))))
              rows)]
        (when (not= statuses @dialogue-display)
          (render!
            #(doseq [{:keys [index id revision recording-status]} statuses]
               (set-passage-freshness! index id revision
                                      (get {:needs-recording 1 :changed 2 :recorded 3
                                            :unverified 4 :interrupted 5} recording-status 0))))
          (reset! dialogue-display statuses))
        (reset! dialogue-display-key key)))))

(defn- message! [text]
  (render! #(status! text)))

(defn- warning! [text]
  (render! #(alert! text)))

(defn fit-window-bounds
  "Restore content bounds on the closest available work area; keep decorations visible.
  This layout currently requires 1100 x 760 content points. GUI scaling is separate."
  [saved displays frame]
  (let [valid? (fn [r] (and (map? r)
                            (every? #(and (integer? (get r %))
                                          (<= -1000000 (get r %) 1000000))
                                    [:x :y :width :height])
                            (pos? (:width r))
                            (pos? (:height r))))
        {:keys [left top right bottom]} (merge {:left 0 :top 0 :right 0 :bottom 0} frame)]
    (when-not (and (valid? saved)
                   (every? #(and (integer? %) (<= 0 % 1000)) [left top right bottom]))
      (throw (ex-info "Invalid studio window bounds." {:code :invalid-window-bounds})))
    (let [areas (filter #(and (valid? %) (>= (- (:width %) left right) 1100)
                              (>= (- (:height %) top bottom) 760)) displays)
          distance (fn [a] (let [cx (+ (:x saved) (/ (:width saved) 2))
                                 cy (+ (:y saved) (/ (:height saved) 2))
                                 dx (- cx (max (:x a) (min cx (+ (:x a) (:width a)))))
                                 dy (- cy (max (:y a) (min cy (+ (:y a) (:height a)))))]
                             (+ (* dx dx) (* dy dy))))
          a (first (sort-by distance areas))]
      (when-not a
        (throw (ex-info "Studio needs a display work area of at least 1100 x 760 points plus window borders."
                        {:code :display-too-small})))
      (let [w (max 1100 (min (:width saved) (- (:width a) left right)))
            h (max 760 (min (:height saved) (- (:height a) top bottom)))]
        {:x (max (+ (:x a) left) (min (:x saved) (- (+ (:x a) (:width a)) right w)))
         :y (max (+ (:y a) top) (min (:y saved) (- (+ (:y a) (:height a)) bottom h)))
         :width w :height h}))))

(defn- native-bounds [f]
  (let [value (f)]
    (try (az/value value) (finally (az/close! value)))))

(defn restore-window! []
  (let [saved (when (.isFile window-file)
                (edn/read-string (slurp window-file)))]
    (when saved
      (when-not (= 1 (:version saved))
        (throw (ex-info "Unsupported studio window settings version." {:code :window-settings-version})))
      (when (and (contains? saved :panels)
                 (not (and (map? (:panels saved))
                           (boolean? (get-in saved [:panels :routing-visible?])))))
        (throw (ex-info "Invalid studio panel settings." {:code :invalid-panel-settings})))
      (when (and (contains? (:panels saved) :editor-top)
                 (not (let [top (get-in saved [:panels :editor-top])]
                        (and (number? top)
                             (Double/isFinite (double top))
                             (<= 316 top 1588)))))
        (throw (ex-info "Invalid studio divider position." {:code :invalid-panel-settings})))
      (render! #(let [frame (native-bounds window-bounds)
                      displays (mapv (fn [i] (native-bounds (fn [] (monitor-bounds i)))) (range (monitor-count)))
                      {:keys [x y width height]} (fit-window-bounds (:bounds saved) displays
                                                                    (select-keys frame [:left :top :right :bottom]))]
                  (apply-window-bounds! x y width height)
                  (update-layout!)
                  (when (contains? saved :panels)
                    (show-routing! (get-in saved [:panels :routing-visible?]))
                    (when-let [top (get-in saved [:panels :editor-top])]
                      (set-editor-top! top))))))))

(defn save-window-preferences!
  "Persist an observed normal window after 500 ms of stability. Explicit dependencies
  let tests use their own file and state, never the running studio's preferences."
  [snapshot destination state now force?]
  (locking state
    (let [current (select-keys snapshot [:x :y :width :height])
          panels (cond-> (if (boolean? (:routing-visible? snapshot))
                           (assoc (:panels @state) :routing-visible? (:routing-visible? snapshot))
                           (:panels @state))
                   (number? (:editor-top snapshot)) (assoc :editor-top (:editor-top snapshot)))
          bounds (if (and (= 1 (:normal snapshot))
                          (>= (:width current) 1100)
                          (>= (:height current) 760))
                   current
                   (:bounds @state))]
      (when bounds
        (when (or (not= bounds (:bounds @state))
                  (not= panels (:panels @state)))
          (reset! state {:bounds bounds :panels panels :changed-at now :saved? false}))
        (when (and (not (:saved? @state))
                   (or force? (not (:error @state)))
                   (or force? (>= (- now (:changed-at @state)) 500000000)))
          (try
            (atomic-write! destination #(spit % (pr-str (cond-> {:version 1 :bounds bounds}
                                                          panels (assoc :panels panels)))))
            (swap! state #(-> % (assoc :saved? true) (dissoc :error)))
            (catch Throwable e
              (swap! state assoc :error (ex-message e))
              (throw e))))))))

(defn save-window!
  "Debounced preferences write on the worker, never the audio/render callback.
  Minimized/maximized dimensions must not replace the last normal window bounds."
  ([] (save-window! false))
  ([force?]
   (try
     (save-window-preferences! (render! #(assoc (native-bounds window-bounds)
                                                :routing-visible? (az/value routing-visible) :editor-top (az/value editor-top)))
                               window-file window-save-state (System/nanoTime) force?)
     (catch Throwable e
       ;; Preference failures must never enter the recording worker's stop-on-error path.
       (when (not= (ex-message e) (:reported-error @window-save-state))
         (swap! window-save-state assoc :reported-error (ex-message e))
         (binding [*out* *err*] (println "Studio window settings:" (ex-message e))))))))

(defn peak-dbfs
  "Peak amplitude, not RMS/loudness. Floor silence at -120 dBFS for display."
  [peak]
  (if (and (number? peak)
           (Double/isFinite (double peak))
           (pos? peak))
    (max -120.0 (* 20.0 (Math/log10 (double peak))))
    -120.0))

(defn routing-signal-state
  "Current device-level evidence, not proof that a particular effect was applied.
  Inactive devices never inherit signal verification from an earlier take."
  [channel {:keys [active? bypassed? phase counting-in? current-peak held-peak source]}]
  (let [title (if (= channel :input)
                (if (= source :take) "Take send" "Input")
                "FX")
        current-db (peak-dbfs current-peak)
        held-db (peak-dbfs held-peak)
        state (cond
                active? (if (> current-db -100.0)
                          :signal-present
                          :listening)
                bypassed? :bypassed
                (= phase 1) (if counting-in? :count-in :preparing)
                :else :not-checked)
        label (case state
                :signal-present (format "%s signal; peak %.0f dBFS" title held-db)
                :listening (str title (if (= phase 3)
                                       ": tail / no signal"
                                       ": listening / no signal"))
                :count-in (str title ": waiting for count-in")
                :preparing (str title ": opening audio devices")
                :bypassed "FX: bypassed (Dry mode)"
                :not-checked (str title ": idle / not checked"))]
    {:state state
     :label label
     :current-dbfs (when active? current-db)
     :peak-dbfs (when active? held-db)
     :effect-verified? false}))

(defn- routing-signal-snapshot
  "Render-thread snapshot, shared by the UI and query API. Does not open devices."
  []
  (let [phase (capture-phase-value)
        dry? (if (>= phase 2)
               (not (capture-fx?))
               (= 1 (az/value record-mode)))]
    (into {}
          (for [[channel input?] [[:input true] [:return false]]]
            [channel (routing-signal-state
                       channel
                       {:active? (meter-active? input?)
                        :bypassed? (and (not input?) dry?)
                        :source (when (and input? (effects-pass?)) :take)
                        :phase phase
                        :counting-in? (counting-in?)
                        :current-peak (recorder/signal-peak input? false)
                        :held-peak (recorder/signal-peak input? true)})]))))

(defn recording-health
  "Advisory thresholds, never destructive gain changes. Input and FX stay distinct."
  [input-peak return-peak]
  (let [input-db (peak-dbfs input-peak)
        fx-db (when (some? return-peak)
                (peak-dbfs return-peak))
        kind (cond
               (or (>= input-peak 1.0)
                   (and return-peak (>= return-peak 1.0)))
               :clipping
               (<= input-db -100.0)
               :silent-input
               (and fx-db (<= fx-db -100.0))
               :silent-return
               (or (< input-db -40.0)
                   (and fx-db (< fx-db -40.0)))
               :low-level
               :else
               :ok)]
    {:kind kind :input-dbfs input-db :return-dbfs fx-db
     :message (case kind
                :clipping "Clipping: lower the mic / FX gain. Take retained."
                :silent-input "No input signal: check the selected mic and its gain. Take retained."
                :silent-return "Input received, but no FX return: check Bitwig input 1/2 and output 3/4."
                :low-level (str (format "Input %.0f dBFS" input-db)
                                (when fx-db
                                  (format " / FX %.0f dBFS" fx-db))
                                ": low level. Check mic / Bitwig gain.")
                nil)}))

(declare compensate-take! checkpoint! begin-recovery! recover! refresh-display! emit-event!
         passage-index)

(defn- save-take! []
  (recorder/stop!)
  (when-let [{:keys [id path processed? dry-path alignment-source]} @session]
    (checkpoint! true)
    (when dry-path
      (when-not (recorder/write-take! dry-path false)
        (throw (ex-info "Native dry WAV encoder failed; buffers retained" {:path dry-path})))
      (remember! id :dry dry-path))
    (when-not (recorder/write-take! path processed?)
      (throw (ex-info "Native WAV encoder failed" {:path path})))
    (remember! id (if processed?
                    :wet
                    :dry) path)
    (render! #(note-take! id))
    (when-let [manifest (:manifest @session)]
      (files/atomic-edn! manifest (assoc (files/read-state manifest {}) :completed? true)))
    (reset! session nil)
    (message! (if (recorder/validate-take! path)
                (str (if dry-path
                       "Dry + processed takes saved, "
                       "Take saved, ")
                     (format "%.2f" (/ (double (az/value recorder/measured-frames)) 48000.0)) " s. Play or publish.")
                "Take retained, but silent / clipped / invalid audio cannot be published."))
    (when (and (or dry-path alignment-source)
               processed?
               (= 1 (render! #(az/value compensate))))
      (compensate-take! id (or dry-path alignment-source) path))
    (let [peak (fn [p] (apply max 0.0 (:bins (files/waveform p))))
          input-path (or dry-path
                         alignment-source
                         path)
          health (recording-health (peak input-path) (when processed?
                                                       (peak path)))]
      (when-let [text (:message health)]
        (warning! (str "Last take: " text)))
      (emit-event! {:type :recording/levels :id id :health health}))
    (emit-event! {:type :recording/saved :id id :path path :dry-path dry-path
                  :selected (get-in @takes [id :selected])})))

(defn- save-interrupted-take! []
  ;; Devices have been stopped before reading their final PCM counts. A missing
  ;; FX return must not prevent retaining the dry recording, or vice versa.
  (when-let [{:keys [id path dry-path processed? manifest]} @session]
    (checkpoint! true)
    (let [streams (cond-> [[(if processed? :wet :dry) path]]
                    dry-path (conj [:dry dry-path]))
          saved (mapv
                  (fn [[kind path]]
                    (let [frames (recorder/available-frames (= kind :wet))]
                      (when (pos? frames)
                        (when-not (recorder/write-take! path (= kind :wet))
                          (throw (ex-info "Interrupted take could not be saved; PCM retained. Stop to retry."
                                          {:path path})))
                        (when-not (some #(= path (:path %)) (get-in @takes [id :history]))
                          (remember! id kind path))
                        (change-takes! "Mark interrupted take"
                          #(update-in % [id :history]
                            (fn [history]
                              (mapv (fn [take]
                                      (if (= path (:path take))
                                        (assoc take :interrupted? true :name "Interrupted take")
                                        take)) history))))
                        {:kind kind :path path :frames frames})))
                  streams)
          retained (vec (remove nil? saved))]
      (when manifest
        (files/atomic-edn! manifest
          (assoc (files/read-state manifest {})
                 :completed? true
                 :interrupted? true)))
      (reset! session nil)
      (render! #(note-take! id))
      (emit-event! {:type :recording/interrupted :id id :saved retained})
      (if (seq retained)
        "Audio device stopped. Partial take saved; check routing and listen before using it."
        "Audio device stopped before any samples arrived. Check routing and record again."))))

(defn- check-playback-devices! []
  (let [outputs (render! #(handle-stopped-outputs!))]
    (when (pos? outputs)
      (emit-event! {:type :audio/output-stopped :mask outputs})
      (warning! "Listening output stopped. Position retained. Play retries; select another output if needed."))))

(defn- check-audio-devices! []
  (let [mask (recorder/stopped-device-mask)]
    (when (pos? mask)
      (emit-event! {:type :audio/device-stopped :mask mask})
      (when (pos? (bit-and mask 8))
        (stop-input-check!)
        (warning! "Input check stopped: audio device unavailable. Check input selection and reconnect."))
      (when (pos? (bit-and mask 4))
        (recorder/stop-monitor!)
        (render! #(az/set-value! monitor-enabled 0))
        (warning! "Monitoring stopped: audio device unavailable. Recording is unaffected if its inputs remain active."))
      (when (pos? (bit-and mask 3))
        (reset! armed nil)
        (when @session
          (swap! session assoc :device-interruption mask))
        (recorder/stop!)
        (let [message (or (save-interrupted-take!)
                          "Audio device stopped. Check routing before recording again.")]
          (render! #(set-native-state! [busy 0
                                        capture-phase 0
                                        monitor-enabled 0]))
          (warning! message))))))

(defn- focused-id []
  (native-string (render! #(selected-id))))

(defn- stop-mix! []
  (mixer/close!)
  (render! #(do (az/set-value! mix-mode false) (az/set-value! preview-paused false))))

(defn- prepare-mix! []
  (stop-mix!)
  (render! #(stop-voice!))
  (let [sources (->> @takes (sort-by key)
                     (keep (fn [[id entry]] (when-let [path (:selected entry)]
                                              {:id id :path path}))) vec)]
    (when (or (empty? sources) (> (count sources) 16))
      (throw (ex-info "Mix requires 1–16 selected takes" {:code :mix-capacity})))
    (mixer/reset!)
    (reset! mix-sources [])
    (try
      (doseq [[i {:keys [path]}] (map-indexed vector sources)]
        (when-not (mixer/add-file! path)
          (throw (ex-info "Cannot load mix: invalid media or total source duration exceeds 120 seconds" {:code :mix-load :path path})))
        ;; Conservative initial mix level; explicit per-clip gain is API-controllable.

        (mixer/configure-clip! i 0 (/ 0.5 (count sources)) 0.0 240 false false))
      (reset! mix-sources (mapv #(assoc % :start 0 :gain (/ 0.5 (count sources)) :pan 0 :fade 0.005 :mute false :solo false) sources))
      (render! #(do (az/set-value! loop-from 0.0)
                    (az/set-value! loop-to (/ (double (az/value mixer/duration)) 48000.0))))
      {:clips (count sources) :frames (az/value mixer/duration)}
      (catch Throwable e (mixer/reset!) (throw e)))))

(defn- play-mix! []
  (az/set-value! mixer/output-index (render! #(az/value headphones)))
  (when-not (mixer/open!)
    (throw (ex-info "Mix output device could not start" {:code :mix-device})))
  (render! #(do (stop-voice!) (az/set-value! mix-mode true)
                (az/set-value! preview-node (az/value selected)) (az/set-value! preview-paused false)))
  (when (>= (mixer/cursor-frame) (az/value mixer/duration))
    (mixer/seek! 0))
  (mixer/play!))

(defn select-take! [forward?]
  (stop-mix!)
  (let [id (focused-id)
        {:keys [history selected]} (get @takes id)
        paths (mapv :path history)]
    (when (empty? paths)
      (throw (ex-info "No takes for this passage." {})))
    (let [index (mod (+ (.indexOf paths selected) (if forward?
                                                    1
                                                    -1)) (count paths))
          entry (nth history index)]
      (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
                    (az/set-value! preview-paused false) (az/set-value! seek-seconds 0.0)))
      (change-takes! "Select take" #(assoc-in % [id :selected] (:path entry)))
      (message! (str "Take " (inc index) "/" (count paths) " — " (if (= :wet (:kind entry))
                                                                   "processed"
                                                                   "dry")))
      entry)))

(defn preview! []
  (stop-mix!)
  (let [path (get-in @takes [(focused-id) :selected])]
    (when-not path
      (throw (ex-info "Select a take." {})))
    (let [{:keys [frames bins]} (files/waveform path)]
      (render! #(az/set-value! audition-source-peak (apply max 0.0 bins)))
      (when-not (render! #(when (play-voice-file! path)
                            (az/set-value! take-seconds (/ frames 48000.0))
                            (az/set-value! preview-node (az/value selected)) (az/set-value! preview-paused false)
                            (seek-preview!) true))
        (throw (ex-info "Audio output unavailable. Select a listening output and try again." {:code :playback-device}))))
    (message! (str "Listening output: " (native-string (recorder/device-name false (render! #(az/value headphones))))))))

(defn publish!
  "Atomically publish a validated processed take; game watches the stable passage path."
  []
  (let [id (focused-id)
        {:keys [selected history]} (get @takes id)
        entry (some #(when (= selected (:path %)) %) history)]
    (when-not (and (re-matches #"[A-Za-z0-9_-]+" id) (= :wet (:kind entry)))
      (throw (ex-info "Select a processed take before publishing." {})))
    (when-not (recorder/validate-take! selected)
      (throw (ex-info "Cannot publish: silent, clipped or invalid audio." {})))
    (let [destination (io/file "resources/voices" (str id ".wav"))]
      (atomic-write! destination #(io/copy (io/file selected) %))
      (change-takes! "Publish to game" #(assoc-in % [id :published] selected) true)
      (message! "Voice published. F1: listen to the passage in the game.")
      (.getCanonicalPath destination))))

(defn- start-take! [processed?]
  (let [id (native-string (render! #(selected-id)))
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Select a voiced passage with a recording ID" {:id id})))
        script (capture-script-snapshot! id)
        provenance (if processed?
                     (take-provenance id (get-in @takes [id :dry]))
                     (select-keys script [:dialogue-hash]))
        capture (render! #(az/value (if processed?
                                      return-input
                                      microphone)))
        playback (render! #(az/value effects-output))
        path (io/file "build/recording" id (str (java.util.UUID/randomUUID) (if processed?
                                                                              "-wet.wav"
                                                                              "-dry.wav")))]
    (when processed?
      (when-not (and (get-in @takes [id :dry])
                     (recorder/load-dry! (get-in @takes [id :dry])))
        (throw (ex-info "Record/load a dry take for this passage first" {:id id})))
      (doseq [name [(native-string (recorder/device-name true capture)) (native-string (recorder/device-name false playback))]]
        (when-not (= "BlackHole 16ch" name)
          (throw (ex-info "Effects pass requires explicit BlackHole 16ch input/output" {:device name})))))
    (io/make-parents path)
    ;; Reserve a fresh take path; never overwrite a previous take.

    (when-not (.createNewFile path)
      (throw (ex-info "Take path already exists" {:path path})))
    (when-not (recorder/start! capture playback (if processed?
                                                  2
                                                  1) (* 48000 (render! #(az/value tail-seconds))))
      (throw (ex-info "Audio device could not start (or no dry take loaded)" {})))
    (reset! session (merge provenance
                          {:id id :script (dissoc script :dialogue-hash)
                           :path (.getCanonicalPath path) :processed? processed?
                           :alignment-source (when processed?
                                               (get-in @takes [id :dry]))}))
    (begin-recovery!)
    (render! #(begin-capture-presentation!))
    (message! (if processed?
                "Real-time effects pass: Bitwig must return audio on 3/4."
                "Recording microphone. Stop to save the take."))))

(defn- start-live-take! []
  (let [id (focused-id)
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Select a passage with a voice ID." {})))
        script (capture-script-snapshot! id)
        capture (render! #(az/value microphone))
        return-index (render! #(az/value return-input))
        send-index (render! #(az/value effects-output))
        stem (str (java.util.UUID/randomUUID))
        dry-path (io/file "build/recording" id (str stem "-dry.wav"))
        wet-path (io/file "build/recording" id (str stem "-wet.wav"))]
    (io/make-parents dry-path)
    (doseq [path [dry-path wet-path]]
      (when-not (.createNewFile path)
        (throw (ex-info "Take path already exists" {:path path}))))
    (when (and (= 1 (render! #(az/value monitor-enabled)))
               (not (recorder/headphone-device? (render! #(az/value headphones)))))
      (throw (ex-info "Connect and select headphones before enabling live monitoring." {})))
    (when-not (recorder/start-live! capture return-index send-index (* 48000 (render! #(az/value tail-seconds))))
      (throw (ex-info "Live FX: cannot open the input and BlackHole 16ch." {})))
    (reset! session {:id id :path (.getCanonicalPath wet-path)
                     :script (dissoc script :dialogue-hash)
                     :dialogue-hash (:dialogue-hash script)
                     :dry-path (.getCanonicalPath dry-path) :processed? true :live? true})
    (begin-recovery!)
    (when (= 1 (render! #(az/value monitor-enabled)))
      (when-not (recorder/start-monitor! return-index (render! #(az/value headphones)))
        (throw (ex-info "Headphones unavailable; input stopped, take recoverable." {}))))
    (render! #(begin-capture-presentation!))
    (message! "LIVE + FX: recording input and return; recovery active.")))

(defn- finish-take! []
  (stop-input-check!)
  (stop-mix!)
  (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
                (az/set-value! seek-seconds 0.0) (az/set-value! preview-paused false)))
  (cond
    @armed
    (do
      (reset! armed nil)
      (render! #(az/set-value! busy 0))
      (message! "Count-in cancelled. Microphone was not opened."))
    (:device-interruption @session)
    (do
      (warning! (save-interrupted-take!))
      (render! #(set-native-state! [busy 0
                                    capture-phase 0])))

    (:live? @session)
    (do
      (recorder/finish-live!)
      (message! "Send stopped. Capturing the effects tail..."))

    :else
    (do
      (save-take!)
      (render! #(az/set-value! busy 0)))))

(defn- recording-passage-index [id]
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9_-]+" id))
    (throw (ex-info "Select a voiced passage to record." {:code :not-recordable})))
  (or (passage-index id)
      (throw (ex-info "Recording passage no longer exists. Select it again."
                      {:code :not-found :id id}))))

(defn- select-recording-target! [id]
  ;; Resolve again after count-in: Markdown can move a passage without changing its ID.
  (render!
    #(let [index (recording-passage-index id)]
       (set-native-state! [selected index
                           record-track index]))))

(defn- schedule-passage! [action id]
  ;; Validate before stopping an audition. Focused recording ignores Edit's arm.
  (render! #(recording-passage-index id))
  (stop-input-check!)
  (when-not (recorder/initialize!)
    (throw (ex-info "Audio inputs unavailable. Reconnect before recording." {})))
  (stop-mix!)
  (select-recording-target! id)
  (capture-script-snapshot! id)
  (render!
    #(do
       (alert! "")
       (stop-voice!)
       (set-native-state! [preview-node 4294967295
                           preview-paused false
                           seek-seconds 0.0
                           busy 1
                           capture-phase 1])
       (begin-countdown-clock!)))
  (reset! armed {:id id
                 :action action
                 :until (+ (System/currentTimeMillis)
                           (* 1000 (render! #(az/value countdown-seconds))))}))

(defn- schedule! [action]
  (let [id (render!
             #(let [index (az/value record-track)]
                (when (>= index (az/value scene/passage-entity-count))
                  (throw (ex-info "Arm a track in Edit, or use Record mode to record the selected passage."
                                  {:code :no-armed-track})))
                (native-string (node-id index))))]
    (schedule-passage! action id)))

(defn- play-transport! []
  (cond
    (render! #(paused-preview?))
    (when-not (render! #(resume-preview!))
      (throw (ex-info "Listening output unavailable. Select an output and press Play to retry."
                      {:code :playback-device})))

    (render! #(and (= 0 (az/value workspace-mode))
                   (= 1 (az/value record-enabled))))
    (schedule! (render! #(az/value record-mode)))

    :else
    (when-not (render! #(playing-preview?))
      (if (render! #(az/value mix-mode))
        (play-mix!)
        (preview!)))))

(defn- begin-recovery! []
  (let [{:keys [id live? processed?]} @session
        dir (io/file "build/recording/recovery" (str (java.util.UUID/randomUUID)))
        kinds (if live?
                [:dry :wet]
                [(if processed?
                   :wet
                   :dry)])
        manifest (io/file dir "session.edn")]
    (io/make-parents manifest)
    (doseq [kind kinds]
      (when-not (.createNewFile (io/file dir (str (name kind) ".pcm")))
        (throw (ex-info "Recovery file exists" {}))))
    (files/atomic-edn! manifest
      (merge (select-keys @session [:dialogue-hash :interrupted?])
             {:id id :completed? false
              :streams (into {} (map (fn [k] [k {:file (str (name k) ".pcm") :frames 0}]) kinds))}))
    (swap! session assoc :manifest (.getCanonicalPath manifest) :checkpoint-at 0)))

(defn- checkpoint! [force?]
  (when-let [{:keys [manifest checkpoint-at]} @session]
    (when (and manifest
               (or force? (> (- (System/currentTimeMillis) checkpoint-at) 1000)))
      (let [state (files/read-state manifest {})
            dir (.getParentFile (io/file manifest))
            updated (update state :streams
                            #(into {} (for [[kind {:keys [file frames] :as entry}] %]
                                        (let [expected (recorder/available-frames (= kind :wet))
                                              end (recorder/journal! (.getCanonicalPath (io/file dir file)) (= kind :wet) frames)]
                                          (when (< end (max frames expected))
                                            (throw (ex-info "Recovery checkpoint failed; PCM retained" {})))
                                          [kind (assoc entry :frames end)]))))]
        (files/atomic-edn! manifest updated)
        (swap! session assoc :checkpoint-at (System/currentTimeMillis))))))

(defn- recover-manifest! [manifest]
  (let [{:keys [id streams] :as state} (files/read-state manifest {})
        origin (fn [kind]
                 {:manifest (.getCanonicalPath (io/file manifest))
                  :kind kind
                  :frames (get-in streams [kind :frames])})
        imported (set (keep :recovery-source (get-in @takes [id :history])))]
    (if (:completed? state)
      0
      (let [recovered (filterv #(not (contains? imported (origin (:kind %))))
                              (files/recover-journal! manifest))]
        ;; One project commit owns both streams. Its persisted origins make a
        ;; retry after manifest-write failure a finalization, not a second import.
        (when (seq recovered)
          (change-takes! "Recover takes"
            (fn [index]
              (reduce
                (fn [index {:keys [id kind path]}]
                  (let [entry (merge (select-keys state [:dialogue-hash])
                                     {:kind kind :path path
                                      :interrupted? true
                                      :recovery-source (origin kind)})]
                    (update index id
                      (fn [passage]
                        (-> (or passage {})
                            (assoc kind path :selected path)
                            (update :history (fnil conj []) entry))))))
                index
                recovered))))
        (files/atomic-edn! manifest (assoc state :completed? true :recovered? true))
        (count recovered)))))

(defn recover! []
  (when (or @session @armed)
    (throw (ex-info "Stop recording before recovery." {})))
  (let [manifests (filter #(and (.isFile %) (= "session.edn" (.getName %)))
                          (file-seq (io/file "build/recording/recovery")))
        count (atom 0)]
    (doseq [manifest manifests :when (not (:completed? (files/read-state manifest {})))]
      (swap! count + (recover-manifest! manifest)))
    (message! (str @count " take(s) recovered. Original PCM retained."))
    @count))

(defn- compensate-take! [id dry-path wet-path]
  (try
    (let [result (files/alignment dry-path wet-path)]
      (if (:accepted? result)
        (let [target (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-aligned.wav"))
              end (quot (alength ^bytes (files/pcm wet-path)) 8)
              path (files/trim! wet-path target (:frames result) end)]
          (remember! id :wet path (take-provenance id wet-path))
          (change-takes! "Align take"
                         #(update-in % [id :history]
                                     (fn [entries] (mapv (fn [e] (if (= (:path e) path)
                                                                   (assoc e :source wet-path :alignment result :name "Aligned return")
                                                                   e)) entries))))
          (message! (format "Aligned copy: %.2f ms. Originals retained." (:milliseconds result))))
        (message! "Uncertain alignment: no audio trimmed. Originals retained.")))
    (catch Exception e (message! (str "Take saved. Alignment rejected: " (.getMessage e))))))

(defn- selected-entry []
  (let [id (focused-id)
        path (get-in @takes [id :selected])]
    [id (or (some #(when (= path (:path %)) %) (get-in @takes [id :history]))
            (throw (ex-info "Select a take." {})))]))

(defn comparison-state
  "Readiness for the selected passage. A reference never silently crosses passages.
   File checks happen on the host, not in the native draw/audio callbacks."
  [take-state reference id]
  (let [path (get-in take-state [id :selected])
        paths (set (map :path (get-in take-state [id :history])))
        available? (fn [candidate]
                     (and (contains? paths candidate)
                          (string? candidate)
                          (.isFile (io/file candidate))
                          (.canRead (io/file candidate))))
        state (cond
                (or (nil? reference) (not= id (:id reference))) :unmarked
                (not (available? (:path reference))) :missing-a
                (not (available? path)) :missing-b
                (= path (:path reference)) :select-b
                (:playing-a? reference) :listen-b
                :else :listen-a)]
    {:state state
     :ready? (contains? #{:listen-a :listen-b} state)
     :a (when (= id (:id reference)) (:path reference))
     :b path}))

(defn- refresh-comparison! [id]
  (let [{:keys [state]} (comparison-state @takes @comparison id)
        code ({:unmarked 0 :select-b 1 :listen-a 2 :listen-b 3 :missing-a 4 :missing-b 5} state)]
    (render! #(comparison-view! id code))))

(defn- edit-take! [operation]
  (let [[id entry] (selected-entry)
        path (:path entry)]
    (case operation
      :name (let [label (native-string (render! #(entered-name)))]
              (when (empty? (.trim label))
                (throw (ex-info "Enter a name." {})))
              (change-takes! "Rename take"
                             #(update-in % [id :history] (fn [entries] (mapv (fn [e] (if (= path (:path e))
                                                                                       (assoc e :name label)
                                                                                       e)) entries))))
              (message! "Name saved."))
      :a (do
           (reset! comparison {:id id :path path :playing-a? false})
           (refresh-comparison! id)
           (message! "Take A marked. Select another take, then listen A/B."))
      :compare (let [a @comparison
                     {:keys [state ready?]} (comparison-state @takes a id)]
                 (when-not ready?
                   (throw (ex-info
                            (case state
                              :select-b "Select a different take as B."
                              :missing-a "Take A is unavailable. Mark another take as A."
                              :missing-b "Take B is unavailable. Select another take."
                              "Mark take A for this passage.")
                            {:code :comparison-not-ready :state state})))
                 (let [next-a? (not (:playing-a? a))
                       target (if next-a?
                                (:path a)
                                path)
                       peak (apply max 0.0 (:bins (files/waveform target)))]
                   (render! #(az/set-value! audition-source-peak peak))
                   (when-not (render! #(when (play-voice-file! target)
                                         ;; A is not the selected B waveform. Global transport
                                         ;; still controls A, but B must not borrow A's cursor.
                                         (az/set-value! preview-node
                                                        (if (= target path)
                                                          (az/value selected)
                                                          4294967295))
                                         (az/set-value! preview-paused false) (az/set-value! seek-seconds 0.0) true))
                     (throw (ex-info "Playback failed." {})))
                   (swap! comparison assoc :playing-a? next-a?)
                   (refresh-comparison! id)
                   (message! (if next-a?
                               "Playing A"
                               "Playing B"))))
      :trim (let [frames (quot (alength ^bytes (files/pcm path)) 8)
                  from (quot (* frames (render! #(az/value trim-in))) 100)
                  to (quot (* frames (render! #(az/value trim-out))) 100)
                  target (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-trim.wav"))]
              (remember! id (:kind entry) (files/trim! path target from to) entry)
              (message! "Trimmed copy created. Original unchanged."))
      :preferred (do
                   (change-takes! "Favorite take" #(assoc-in % [id :preferred] path))
                   (message! "Favorite take saved.")))))

(defn- device-names [capture?]
  (mapv #(native-string (recorder/device-name capture? %))
        (range (az/value (if capture?
                           recorder/capture-count
                           recorder/playback-count)))))

(defn- routing! [operation]
  (case operation
    :save (let [name (native-string (render! #(entered-name)))
                ins (device-names true)
                outs (device-names false)]
            (when (empty? (.trim name))
              (throw (ex-info "Enter a profile name." {})))
            (swap! presets assoc name {:source (nth ins (render! #(az/value microphone)))
                                       :return (nth ins (render! #(az/value return-input)))
                                       :send (nth outs (render! #(az/value effects-output)))
                                       :headphones (nth outs (render! #(az/value headphones)))
                                       :tail (render! #(az/value tail-seconds))
                                       :countdown (render! #(az/value countdown-seconds))})
            (reset! active-preset name)
            (files/atomic-edn! presets-file @presets)
            (message! (str "Profile saved: " name)))
    :next (let [names (vec (sort (keys @presets)))]
            (when (empty? names)
              (throw (ex-info "Save a profile first." {})))
            (reset! active-preset (nth names (mod (inc (.indexOf names @active-preset)) (count names))))
            (message! (str "Profile selected: " @active-preset ". Reconnect to apply.")))
    :connect (let [preset (or (get @presets @active-preset)
                              (throw (ex-info "Select a profile." {})))]
               (when-not (and (every? string? (map preset [:source :return :send :headphones]))
                              (#{0 1 3 5} (:tail preset))
                              (#{0 3} (:countdown preset)))
                 (throw (ex-info "Invalid routing profile." {})))
               ;; Re-enumeration invalidates indices. Failure must not capture a different microphone.
               ;; Device names are also read by draw!: replace the context between frames.

               (stop-mix!)
               (render! #(do (close-playback!) (az/set-value! monitor-enabled 0)
                             (doseq [field [microphone return-input effects-output headphones]]
                               (az/set-value! field 4294967294))
                             (recorder/shutdown!)
                             (when-not (recorder/initialize!)
                               (throw (ex-info "Audio device enumeration failed." {})))))
               (let [ins (device-names true)
                     outs (device-names false)
                     values [(files/resolve-device ins (:source preset)) (files/resolve-device ins (:return preset))
                             (files/resolve-device outs (:send preset)) (files/resolve-device outs (:headphones preset))]]
                 (doseq [[field value] (map vector [microphone return-input effects-output headphones] values)]
                   (render! #(az/set-value! field value)))
                 (render! #(do (az/set-value! tail-seconds (:tail preset)) (az/set-value! countdown-seconds (:countdown preset))
                               (az/set-value! monitor-enabled 0))))
               (message! "Routing reconnected. Live monitoring disabled for safety."))
    :monitor (if (= 1 (render! #(az/value monitor-enabled)))
               (do
                 (recorder/stop-monitor!)
                 (render! #(az/set-value! monitor-enabled 0)))
               (let [index (render! #(az/value headphones))]
                 (when-not (recorder/headphone-device? index)
                   (throw (ex-info "Headphones required. Speakers and loopback are not allowed." {})))
                 (render! #(az/set-value! monitor-enabled 1))
                 (message! (str "Headphone monitoring enabled for the next FX take ("
                                (render! #(monitor-level)) "%, max 50%)."))))))

(defn- take-grid-specs [ids take-state]
  (vec
    (for [id ids
          kind [:dry :wet :preferred]
          :let [passage (get take-state id)
                selected-entry (some #(when (= (:selected passage) (:path %)) %) (:history passage))
                ;; Show the selected version in its own column, even if it is
                ;; an older/trimmed take. Otherwise the editor has no matching card.
                path (if (= kind (:kind selected-entry))
                       (:path selected-entry)
                       (get passage kind))
                entry (some #(when (= path (:path %)) %) (:history passage))]]
      {:id id
       :kind kind
       :path (when entry path)
       :stamp (when entry
                (let [file (io/file path)]
                  [(.isFile file) (.length file) (.lastModified file)]))
       :name (or (:name entry)
                 (when entry (str "Take " (inc (.indexOf (vec (:history passage)) entry)))))
       :chosen? (and (some? entry) (= path (:selected passage)))})))

(defn- take-card-waveform [{:keys [path stamp] :as spec}]
  (if-not path
    (assoc spec :available? false :missing? false :seconds 0.0 :bins (vec (repeat 32 0.0)))
    (try
      (when-not (first stamp)
        (throw (ex-info "Take media is missing" {})))
      (let [wave (or (get @waveform-cache [path stamp])
                     (let [data (files/waveform path)]
                       (swap! waveform-cache assoc [path stamp] data)
                       data))]
        (assoc spec
               :available? true
               :missing? false
               :seconds (/ (:frames wave) 48000.0)
               :bins (mapv #(apply max 0.0 %) (partition 4 (:bins wave)))))
      (catch Exception _
        ;; A missing/corrupt file is one unavailable card, not a dead UI worker.
        (assoc spec :available? false :missing? true :seconds 0.0 :bins (vec (repeat 32 0.0)))))))

(defn- refresh-take-grid! []
  (when (= 2 (render! #(az/value workspace-mode)))
    (let [[offset rows ids] (render!
                             #(let [offset (az/value track-offset)
                                    rows (visible-row-count)
                                    total (az/value scene/passage-entity-count)]
                                [offset rows (mapv (fn [i] (native-string (node-id i)))
                                                   (range offset (min total (+ offset rows))))]))
          specs (take-grid-specs ids @takes)
          key [offset rows specs]]
      (when (not= key (:key @take-grid-cache))
        (let [cards (mapv take-card-waveform specs)
              revision (inc (or (:revision @take-grid-cache) 0))]
          (render!
            #(do
               (doseq [[slot {:keys [available? missing? chosen? seconds name bins]}] (map-indexed vector cards)]
                 (upload-take-card! slot available? missing? chosen? seconds (or name ""))
                 (doseq [[bin peak] (map-indexed vector bins)]
                   (upload-take-card-bin! slot bin peak)))
               (set-native-state! [take-grid-offset offset
                                    take-grid-rows (count ids)
                                    take-grid-revision revision])))
          (reset! take-grid-cache {:key key :revision revision :cards cards}))))))

(defn- upload-selected-waveform! [id upload!]
  (render!
    #(when (= id (native-string (waveform-passage-id)))
       (upload!)
       (waveform-owner! id)
       true)))

(defn- refresh-selected-waveform! [id path entry recording?]
  (let [key [id path]]
    (cond
      (and recording? (= id (:id @session)))
      (let [processed? (:processed? @session)
            bins (mapv #(recorder/wave-bin processed? %) (range 128))]
        ;; The live waveform overwrites the saved-take display. Invalidate even
        ;; if no new take is ultimately selected (cancel/recovery/QA restore).
        (reset! display-cache nil)
        (upload-selected-waveform!
          id
          #(doseq [[i value] (map-indexed vector bins)]
             (set-wave! i value))))

      (and (= key @display-cache)
           (render! #(selected-waveform?)))
      nil

      path
      (let [{:keys [bins frames]} (files/waveform path)]
        (when (upload-selected-waveform!
                id
                #(do
                   (set-native-state! [take-seconds (/ frames 48000.0)
                                       trim-in 0
                                       trim-out 100])
                   (if (not= key @display-cache)
                     (reset-take-name! (or (:name entry) ""))
                     (when-not (name-focused?)
                       (name! (or (:name entry) ""))))
                   (doseq [[i value] (map-indexed vector bins)]
                     (set-wave! i value))))
          (reset! display-cache key)))

      :else
      (when (upload-selected-waveform!
              id
              #(do
                 (az/set-value! take-seconds 0.0)
                 (reset-take-name! "")
                 (dotimes [i 128]
                   (set-wave! i 0.0))))
        (reset! display-cache key)))))

(defn- refresh-display! []
  (save-window!)
  (refresh-dialogue-status!)
  (refresh-take-grid!)
  (render!
    #(let [{:keys [input return]} (routing-signal-snapshot)]
       (meter-labels! (:label input) (:label return))))
  ;; Virtualized, fixed-height rows. Decode immutable files off the render thread
  ;; and upload only when the viewport/content changes (never on every meter tick).

  (let [[offset visible ids] (render! #(let [offset (az/value track-offset) count (az/value scene/passage-entity-count)
                                             visible (visible-row-count)]
                                         [offset visible (mapv (fn [i] (native-string (node-id i)))
                                                               (range offset (min count (+ offset visible))))]))
        take-state @takes
        mix-state @mix-sources
        specs (mapv (fn [id] {:id id :path (get-in take-state [id :selected])
                              :count (count (get-in take-state [id :history]))
                              :start (or (:start (some #(when (= id (:id %)) %) mix-state)) 0.0)}) ids)
        key [offset visible specs (boolean @session)]]
    (when (not= key @clip-display-cache)
      (let [rows (mapv (fn [{:keys [path] :as spec}]
                         (let [data (when path
                                      (or (get @waveform-cache path)
                                          (let [v (files/waveform path)]
                                            (swap! waveform-cache assoc path v)
                                            v)))]
                           (assoc spec :seconds (/ (or (:frames data) 0) 48000.0)
                                  :bins (or (:bins data) (repeat 128 0.0))))) specs)]
        (render! #(do (doseq [[slot {:keys [seconds bins count start]}] (map-indexed vector rows)]
                        (clip! slot seconds count)
                        (clip-start! slot start)
                        (doseq [[bin value] (map-indexed vector bins)]
                          (clip-bin! slot bin value)))
                      (az/set-value! track-snapshot offset)
                      (az/set-value! track-snapshot-count (count rows))
                      (az/set-value! clip-upload-revision (inc (az/value clip-upload-revision)))))
        (reset! clip-display-cache key))))
  (let [id (render! #(native-string (waveform-passage-id)))
        path (get-in @takes [id :selected])
        entry (some #(when (= path (:path %)) %) (get-in @takes [id :history]))
        recording? (boolean @session)]
    (refresh-selected-waveform! id path entry recording?)
    (refresh-comparison! id)
    (let [text (cond
                 @armed
                 (str "Starting in " (max 0 (long (Math/ceil (/ (- (:until @armed) (System/currentTimeMillis)) 1000.0)))) " s — Stop to cancel")
                 recording?
                 (if (recorder/tail-active?)
                   "FX TAIL: send stopped, return still recording. Saves automatically when finished."
                   (format "REC %.1f s | PCM recovery active | normalized waveform" (/ (recorder/frames-recorded) 48000.0)))
                 :else
                 (str (or (:name entry)
                          (when entry
                            (str "Take " (inc (.indexOf (vec (get-in @takes [id :history])) entry)))))
                      " | " (name (or (:kind entry) :none))
                      " | trim " (render! #(az/value trim-in)) "–" (render! #(az/value trim-out)) "%"
                      (when (and path (= path (get-in @takes [id :preferred])))
                        " | favorite")
                      (when @active-preset
                        (str " | " @active-preset))))]
      (render! #(details! text)))))

(defn import-dry!
  "Import an existing WAV for the focused passage; the effects button records its return."
  [path]
  (when (render! #(busy?))
    (throw (ex-info "Stop the current take before importing" {})))
  (render! #(az/set-value! busy 1))
  (try
    (let [id (native-string (render! #(selected-id)))
          _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
              (throw (ex-info "Select a voiced passage first" {})))
          destination (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-dry.wav"))]
      (when-not (recorder/load-dry! (.getCanonicalPath (io/file path)))
        (throw (ex-info "WAV could not be decoded within the recording limit" {:path path})))
      (io/make-parents destination)
      (when-not (.createNewFile destination)
        (throw (ex-info "Take already exists" {})))
      (when-not (recorder/write-take! (str destination) false)
        (throw (ex-info "Cannot save dry take" {})))
      (remember! id :dry (.getCanonicalPath destination))
      (message! "Dry take imported. Process FX records the processed return.")
      (get @takes id))
    (finally (render! #(az/set-value! busy 0)))))

(def workspace-modes
  "Built-in view descriptors shared by API discovery and validation. New modes
  add native layout/input functions, not a second engine or command worker.
  Render symbols document the native entry points; these are not JVM callbacks."
  {:edit {:label "Edit" :native-id 0 :render 'draw-edit-workspace!
          :description "Timeline, script overview and non-destructive take editing."}
   :record {:label "Record" :native-id 1 :render 'draw-record-workspace!
            :description "Select a passage and record a new take directly; no manual arm step."}
   :takes {:label "Takes" :native-id 2 :render 'draw-takes-workspace!
           :description "Dry, processed and favorite take cards, with explicit audition and shared editing."}})

(def ^:private core-commands
  {:transport/play {}
   :transport/pause {}
   :transport/toggle {}
   :transport/stop {}
   :alert/dismiss {}
   :mix/prepare {}
   :mix/play {}
   :mix/stop {}
   :mix/toggle {}
   :mix/loop {:from :nonnegative-number :to :nonnegative-number :enabled :boolean}
   :mix/clip {:id :string :start :nonnegative-number :gain :nonnegative-number :pan :finite-number
              :fade :nonnegative-number :mute :boolean :solo :boolean}
   :project/undo {}
   :project/redo {}
   :transport/launch {}
   :transport/audition {}
   :transport/rewind {}
   :transport/seek {:seconds :nonnegative-number}
   :selection/passage {:id :string}
   :view/zoom {:factor :positive-number}
   :view/pan {:seconds :finite-number}
   :view/routing {:visible :boolean}
   :view/routing-tools {:visible :boolean}
   :view/editor {:top :nonnegative-number}
   :view/mode {:mode :workspace-mode}
   :take/previous {}
   :take/next {}
   :take/select {:id :string :path :take-path}
   :take/audition {:id :string :path :take-path}
   :take/card {:slot :card-slot :revision :nonnegative-integer :audition :boolean}
   :take/name {:name :string}
   :take/mark-a {}
   :take/compare {}
   :take/trim {:from :percentage :to :percentage}
   :take/preferred {}
   :take/publish {}
   :take/recover {}
   :record/dry {}
   :record/fx {}
   :record/start {:id :string}
   :record/process {}
   :record/toggle {}
   :record/enable {:enabled :boolean}
   :record/arm {:id :string :enabled :boolean}
   :playback/boost {:enabled :boolean}
   :routing/select {:source :device-index :send :device-index :return :device-index :headphones :device-index}
   :routing/save {}
   :routing/next {}
   :routing/connect {}
   :routing/check-input {:enabled :boolean}
   :routing/monitor-level {:percent :monitor-percentage}
   :routing/monitor {}})

(defn capabilities
  "Discover the implemented API, not future roadmap features. Extensions run on the control worker."
  []
  {:api-version 1 :transport :take-or-mix :multitrack? true :arranger? false :queue-capacity 64
   :workspace-modes (into {} (map (fn [[mode spec]] [mode (dissoc spec :render)]) workspace-modes))
   :mix {:max-clips 16 :source-seconds 120 :sample-rate 48000 :channels 2 :persistent? false :loop? true}
   :event-retention 256 :request-retention 256 :project-schema 1 :undo-retention 64
   :commands (merge core-commands (into {} (map (fn [[op spec]] [op (dissoc spec :handler :validate)]) @extensions)))})

(defn- emit-event! [event]
  (swap! event-log (fn [{:keys [sequence events]}]
                     (let [event (assoc event :sequence (inc sequence) :time-ms (System/currentTimeMillis))]
                       {:sequence (inc sequence) :events (vec (take-last 256 (conj events event)))}))))

(defn events-since
  "Cursor-based event history. A lagging client must query a fresh snapshot when :resync? is true."
  [cursor]
  (when-not (and (integer? cursor) (<= 0 cursor))
    (throw (ex-info "Invalid event cursor" {:code :invalid-argument})))
  (let [{:keys [sequence events]} @event-log]
    {:cursor sequence :resync? (or (> cursor sequence)
                                   (< cursor (dec (or (:sequence (first events)) 1))))
     :events (filterv #(> (:sequence %) cursor) events)}))

(defn summarize-frame-timings
  "Bounded diagnostics. Render-call time includes CPU work/waits, not GPU timestamps."
  [samples]
  (let [rows (vec samples)
        sample-count (count rows)
        distribution (fn [key]
                       (when (pos? sample-count)
                         (let [ordered (vec (sort (map key rows)))
                               percentile (fn [p]
                                            (nth ordered (dec (long (Math/ceil (* p sample-count))))))]
                           {:mean (/ (reduce + ordered) sample-count)
                            :p95 (percentile 0.95)
                            :p99 (percentile 0.99)
                            :max (peek ordered)})))
        intervals (distribution :interval-ms)]
    {:samples sample-count
     :capacity 240
     :fps (when (and intervals (pos? (:mean intervals))) (/ 1000.0 (:mean intervals)))
     :frame-interval-ms intervals
     :ui-build-ms (distribution :build-ms)
     :render-call-ms (distribution :render-ms)
     :intervals-over-16.67-ms (count (filter #(> (:interval-ms %) 16.67) rows))}))

(defn performance-snapshot
  "Off-render API: last 240 submitted-frame intervals for the Studio window.
  Hidden/minimized gaps are excluded; visible stalls and other-window work remain."
  []
  (summarize-frame-timings
    (mapv (fn [sample]
            ;; Arrays of native structs cross this boundary as raw ABI bytes.
            (let [buffer (doto (ByteBuffer/wrap (byte-array (map unchecked-byte sample)))
                           (.order (ByteOrder/nativeOrder)))]
              {:interval-ms (.getFloat buffer 0)
               :build-ms (.getFloat buffer 4)
               :render-ms (.getFloat buffer 8)}))
          (render! #(vec (take (az/value frame-timing-count) (az/value frame-timings)))))))

(defn query
  "Immutable control-plane snapshot. Call from an nREPL/client thread, never an audio/render callback."
  []
  (merge {:api-version 1 :open? (boolean @worker) :event-cursor (:sequence @event-log)
          :dialogue {:source-state (:dialogue @core/status)
                     :visible-passages (mapv #(dissoc % :text) @dialogue-display)}
          :worker (worker-status)
          :input-check (when-let [check @input-check]
                         (assoc (select-keys check [:source :fx?])
                                :remaining-seconds (max 0.0 (/ (- (:deadline-ns check) (System/nanoTime)) 1e9))))
          :performance (performance-snapshot)
          :project (when-let [p @project]
                     {:id (:project-id p) :revision (:revision p)
                      :undo (mapv :label (:undo p)) :redo (mapv :label (:redo p))})
          :mix {:sources @mix-sources :frames (mixer/cursor-frame) :duration (az/value mixer/duration)
                :playing? (mixer/playing?)
                :loop (let [v (mixer/loop-state)]
                        (try (let [state (az/value v)]
                               {:from-frame (:from state) :to-frame (:to state) :enabled (:enabled state)})
                             (finally (az/close! v))))}
          :comparison (comparison-state @takes @comparison (focused-id))
          :recording (some-> @session (select-keys [:id :path :processed? :script :dialogue-hash]))
          :countdown (some-> @armed (select-keys [:until :action])) :takes @takes :routing-profile @active-preset}
         (render! #(hash-map :selection (native-string (selected-id))
                             :record-control {:enabled? (= 1 (az/value record-enabled))
                                              :armed-index (az/value record-track)
                                              :mode (if (= 8 (az/value record-mode))
                                                      :fx
                                                      :dry)
                                              :phase (capture-phase-value)
                                              :count-in-seconds (az/value countdown-seconds)}
                             :audio-focus {:game-suppressed? (game-audio-suppressed?)}
                             :alert (native-string (current-alert))
                             :signal {:input-peak (recorder/signal-peak true true)
                                      :return-peak (recorder/signal-peak false true)}
                             :routing-signal (routing-signal-snapshot)
                             :monitoring {:enabled? (= 1 (az/value monitor-enabled))
                                          :level-percent (monitor-level)
                                          :max-percent 50}
                             :playback {:output-index (az/value playback-output)
                                        :interrupted? (az/value playback-interrupted)
                                        :audition-gain (az/value audition-gain)
                                        :peak (/ (double (playback-peak-value)) 1000000.0)
                                        :signal-frames (playback-signal-count)}
                             :devices {:inputs (mapv (fn [i] (native-string (recorder/device-name true i))) (range (az/value recorder/capture-count)))
                                       :outputs (mapv (fn [i] (native-string (recorder/device-name false i))) (range (az/value recorder/playback-count)))
                                       :selected {:source (az/value microphone) :send (az/value effects-output)
                                                  :return (az/value return-input) :headphones (az/value headphones)}}
                             :transport {:playing? (playing-preview?) :paused? (az/value preview-paused)
                                         :seconds (cursor-seconds)}
                             :view {:start (az/value timeline-start) :seconds (az/value timeline-seconds)
                                    :mode (some (fn [[mode spec]]
                                                  (when (= (:native-id spec) (az/value workspace-mode)) mode))
                                                workspace-modes)
                                    :routing-visible? (az/value routing-visible)
                                    :routing-tools-visible? (az/value routing-tools-visible)
                                    :editor-top (az/value editor-top) :visible-rows (visible-row-count)
                                    :loop-selection {:from (az/value loop-from) :to (az/value loop-to)}
                                    :track-offset (az/value track-offset) :follow? (az/value follow-playhead)}))))

(defn register-command!
  "Register trusted dev tooling, not untrusted plugins. Handler receives args; it must not block indefinitely."
  [op {:keys [description validate handler] :as spec}]
  (when-not (and (qualified-keyword? op)
                 (not (contains? core-commands op))
                 (string? description)
                 (ifn? validate)
                 (ifn? handler))
    (throw (ex-info "Extension needs a namespaced command, description, validator and handler" {:code :invalid-extension})))
  (swap! extensions assoc op spec)
  op)

(defn unregister-command! [op]
  (swap! extensions dissoc op)
  op)

(defn- valid-argument? [kind value]
  (case kind
    :workspace-mode (contains? workspace-modes value)
    :take-path (and (string? value) (<= 1 (count value) 4096))
    :card-slot (and (integer? value) (<= 0 value 47))
    :nonnegative-integer (and (integer? value) (<= 0 value Long/MAX_VALUE))
    :device-index (and (integer? value) (<= 0 value 255))
    :string (and (string? value) (<= 1 (count value) 120))
    :finite-number (and (number? value)
                        (Double/isFinite (double value))
                        (<= (abs (double value)) 3600.0))
    :nonnegative-number (and (valid-argument? :finite-number value) (<= 0 value))
    :positive-number (and (valid-argument? :finite-number value)
                          (< 0 value)
                          (<= value 30))
    :boolean (boolean? value)
    :monitor-percentage (and (integer? value) (<= 0 value 50))
    :percentage (and (integer? value) (<= 0 value 100)) false))

(defn- validate-command! [{:keys [op args] :as command}]
  (when (and (contains? command :expected-revision)
             (not (and (integer? (:expected-revision command))
                       (<= 0 (:expected-revision command)))))
    (throw (ex-info "Expected revision must be a nonnegative integer" {:code :invalid-argument})))
  (when-not (and (map? command)
                 (keyword? op)
                 (map? args))
    (throw (ex-info "Expected {:op keyword :args map}" {:code :invalid-command})))
  (if-let [schema (get core-commands op)]
    (when-not (and (= (set (keys args)) (set (keys schema)))
                   (every? (fn [[key kind]] (valid-argument? kind (get args key))) schema)
                   (or (not (#{:take/trim :mix/loop} op))
                       (< (:from args) (:to args))))
      (throw (ex-info "Invalid command arguments" {:code :invalid-argument :op op :schema schema})))
    (if-let [extension (get @extensions op)]
      (when-not ((:validate extension) args)
        (throw (ex-info "Invalid extension arguments" {:code :invalid-argument :op op})))
      (throw (ex-info "Unknown command" {:code :unknown-command :op op}))))
  command)

(defn result
  "Read a request result without resubmitting. Unknown/expired requests are explicit."
  [id]
  (if-let [{:keys [completion]} (get @request-ledger id)]
    (deref completion 0 {:request-id id :status :pending})
    {:request-id id :status :unknown}))

(defn submit!
  "Queue an idempotent command on the same worker as the UI. No new network listener is opened."
  [{:keys [request-id] :as request}]
  (let [command (validate-command! (merge {:args {}} (dissoc request :request-id)))
        id (or request-id (str (java.util.UUID/randomUUID)))]
    (when-not (and (string? id) (<= 1 (count id) 128))
      (throw (ex-info "Invalid request id" {:code :invalid-argument})))
    (locking command-queue
      (if-let [existing (get @request-ledger id)]
        (do
          (when-not (= command (:command existing))
            (throw (ex-info "Request id already used for another command" {:code :request-conflict :request-id id})))
          {:request-id id :status :known})
        (do
          (when-not @worker
            (throw (ex-info "Open the studio before sending commands" {:code :closed})))
          (when (future-done? @worker)
            (throw (ex-info "Studio worker stopped. Inspect :worker and call restart-worker! after fixing the error."
                            {:code :worker-stopped})))
          (let [ticket {:request-id id :command command :completion (promise)}]
            (swap! request-ledger assoc id ticket)
            (when-not (.offer command-queue ticket)
              (swap! request-ledger dissoc id)
              (throw (ex-info "Command queue full" {:code :queue-full})))
            {:request-id id :status :queued}))))))

(defn command!
  "Submit and wait up to five seconds. :pending is not failure; poll result with its request-id."
  [request]
  (when *command-worker?*
    (throw (ex-info "A command handler cannot synchronously wait on its own worker; use submit! for follow-up work"
                    {:code :reentrant-command})))
  (let [{:keys [request-id]} (submit! request)]
    (if-let [completion (:completion (get @request-ledger request-id))]
      (deref completion 5000 {:request-id request-id :status :pending})
      {:request-id request-id :status :unknown})))

(defn- complete-request! [{:keys [request-id completion]} response]
  (let [response (assoc response :request-id request-id)]
    (deliver completion response)
    (emit-event! (assoc response :type :command/completed))
    (locking command-queue
      (let [ids (conj @completed-requests request-id)
            expired (drop-last 256 ids)]
        (swap! request-ledger #(apply dissoc % expired))
        (reset! completed-requests (vec (take-last 256 ids)))))))

(defn- configure-mix-loop! [{:keys [from to enabled]}]
  (let [from-frame (Math/round (* 48000.0 (double from)))
        to-frame (Math/round (* 48000.0 (double to)))]
    (when (empty? @mix-sources)
      (throw (ex-info "Prepare the mix before enabling looping."
                      {:code :mix-unprepared})))
    (when (or (>= from-frame to-frame)
              (> to-frame (az/value mixer/duration))
              (not (mixer/set-loop! from-frame to-frame enabled)))
      (throw (ex-info "The loop must stay inside the mix, with A before B."
                      {:code :invalid-loop})))
    (render!
      #(set-native-state! [loop-from (/ from-frame 48000.0)
                           loop-to (/ to-frame 48000.0)]))
    {:from-frame from-frame
     :to-frame to-frame
     :enabled enabled}))

(defn- configure-mix-clip! [{:keys [id start gain pan fade mute solo] :as settings}]
  (let [index (first (keep-indexed (fn [index source]
                                   (when (= id (:id source))
                                     index))
                                 @mix-sources))]
    (when-not index
      (throw (ex-info "Unknown mix clip" {:code :not-found})))
    (when (az/value mixer/opened)
      (throw (ex-info "Stop mix before editing its plan" {:code :mix-busy})))
    (when-not (mixer/configure-clip! index (long (* 48000 start)) gain pan
                                   (long (* 48000 fade)) mute solo)
      (throw (ex-info "Invalid mix clip settings" {:code :invalid-argument})))
    (swap! mix-sources update index merge settings)))

(defn- change-project-history! [direction]
  (stop-mix!)
  (render!
    #(do
       (stop-voice!)
       (set-native-state! [preview-node 4294967295
                           seek-seconds 0.0
                           preview-paused false])))
  (let [next-project (files/history-project! project project-file direction)]
    (reset! takes (:takes next-project))
    (reset! display-cache nil)
    (reset! comparison nil)
    (let [entry (get (:takes next-project) (focused-id))
          selected-take (some #(when (= (:selected entry) (:path %)) %)
                              (:history entry))
          label (or (:name selected-take) "")]
      (render! #(name! label)))
    (message! (if (= direction :undo)
                "Edit undone. Audio retained."
                "Edit redone."))
    {:revision (:revision next-project)}))

(defn- passage-index
  "Render-thread lookup. IDs are authored voice IDs, not mutable row positions."
  [id]
  (first
    (filter (fn [index]
              (= id (native-string (node-id index))))
            (range (az/value scene/passage-entity-count)))))

(defn- select-passage! [id]
  (render!
    #(let [index (passage-index id)]
       (when-not index
         (throw (ex-info "Unknown passage" {:code :not-found :id id})))
       (let [rows (if (= 1 (az/value workspace-mode))
                    (record-row-count)
                    (visible-row-count))
             offset (az/value track-offset)
             next-offset (cond
                           (< index offset) index
                           (>= index (+ offset rows)) (inc (- index rows))
                           :else offset)]
         (set-native-state! [selected index
                             track-offset next-offset
                             record-scroll 0.0
                             focus-scroll 0.0])))))

(defn- arm-passage! [id enabled?]
  (render!
    #(let [index (passage-index id)]
       (when (or (empty? id) (nil? index))
         (throw (ex-info "Unknown voiced passage" {:code :not-found})))
       ;; Disarming another passage must not disarm the current recording target.
       (when (or enabled? (= index (az/value record-track)))
         (az/set-value! record-track (if enabled? index 4294967295))))))

(defn- select-routing! [{:keys [source send return]
                        output-index :headphones
                        :as routing}]
  (render!
    (fn []
      (let [input-count (az/value recorder/capture-count)
            output-count (az/value recorder/playback-count)]
        ;; Validate the complete route before closing an engine or changing fields.
        (when-not (and (< source input-count)
                      (< return input-count)
                      (< send output-count)
                      (< output-index output-count))
          (throw (ex-info "Audio device index is no longer available; refresh devices."
                          {:code :not-found})))
        (when (not= output-index (az/value headphones))
          (close-playback!)
          (mixer/close!)
          (az/set-value! preview-paused false))
        (set-native-state! [microphone source
                            effects-output send
                            return-input return
                            headphones output-index])
        routing))))

(defn- name-selected-take! [text]
  (let [bytes (.getBytes ^String text "UTF-8")]
    (when (> (alength bytes) 120)
      (throw (ex-info "Name exceeds 120 UTF-8 bytes" {:code :invalid-argument})))
    (render! #(name! text)))
  (edit-take! :name))

(defn- trim-selected-take! [from to]
  (refresh-display!)
  (render!
    #(set-native-state! [trim-in from
                         trim-out to]))
  (edit-take! :trim))

(defn- known-take [take-state id path]
  (or (some #(when (= path (:path %)) %) (get-in take-state [id :history]))
      (throw (ex-info "Take does not belong to this passage" {:code :not-found}))))

(defn- select-take-path! [id path]
  ;; Resolve both targets before stopping sound or changing any project state.
  (let [index (render! #(passage-index id))
        entry (known-take @takes id path)]
    (when (nil? index)
      (throw (ex-info "Unknown passage" {:code :not-found})))
    (stop-mix!)
    (render!
      #(do
         (stop-voice!)
         (set-native-state! [selected index
                              preview-node 4294967295
                              preview-paused false
                              seek-seconds 0.0
                              focus-scroll 0.0])))
    (when (not= path (get-in @takes [id :selected]))
      (change-takes! "Select take" #(assoc-in % [id :selected] path)))
    (refresh-display!)
    entry))

(defn- take-card-command [snapshot slot revision audition?]
  (when (not= revision (:revision snapshot))
    (throw (ex-info "Take grid changed; select the card again" {:code :stale-view})))
  (let [{:keys [id path available?]} (get (:cards snapshot) slot)]
    (when-not (and available? (seq id) (seq path))
      (throw (ex-info "This take card has no available audio" {:code :not-found})))
    {:op (if audition? :take/audition :take/select)
     :args {:id id :path path}}))

(defn- audition-transport! []
  (cond
    (render! #(and (selected-preview?) (playing-preview?)))
    (render! #(pause-preview!))

    (render! #(and (selected-preview?) (az/value preview-paused)))
    (when-not (render! #(resume-preview!))
      (throw (ex-info "Listening output unavailable. Select an output and press Play to retry."
                      {:code :playback-device})))

    :else
    (preview!)))

(defn- audition-take! [id path]
  (known-take @takes id path)
  (when-not (and (= id (focused-id)) (= path (get-in @takes [id :selected])))
    (select-take-path! id path))
  (audition-transport!))

(defn- toggle-record-enabled! []
  (render! #(az/set-value! record-enabled (- 1 (az/value record-enabled))))
  (message! (if (= 1 (render! #(az/value record-enabled)))
              "REC ready. Arm a track, select input, then Play."
              "REC disabled. Playback only.")))

(defn- dispatch-command! [op args]
  (case op
    :mix/prepare (prepare-mix!)
    :mix/play (play-mix!)
    :mix/stop (stop-mix!)
    :mix/toggle
    (if (render! #(az/value mix-mode))
      (stop-mix!)
      (do
        (prepare-mix!)
        (play-mix!)))
    :mix/loop (configure-mix-loop! args)
    :mix/clip (configure-mix-clip! args)

    :project/undo (change-project-history! :undo)
    :project/redo (change-project-history! :redo)

    :transport/stop (finish-take!)
    :transport/pause (render! #(pause-preview!))
    :transport/rewind (render! #(position! 0.0))
    :transport/seek (render! #(position! (:seconds args)))
    :transport/play (play-transport!)
    :transport/toggle
    (if (render! #(playing-preview?))
      (render! #(pause-preview!))
      (play-transport!))
    :transport/launch
    (do
      (render! #(az/set-value! seek-seconds 0.0))
      (preview!))
    :transport/audition (audition-transport!)

    :selection/passage (select-passage! (:id args))
    :view/zoom (render! #(zoom-at! (:factor args) (timeline-center)))
    :view/pan (render! #(pan! (:seconds args)))
    :view/routing (render! #(show-routing! (:visible args)))
    :view/routing-tools (render! #(show-routing-tools! (:visible args)))
    :view/editor
    (render!
      #(do
         (az/set-value! divider-drag false)
         (set-editor-top! (:top args))))
    :view/mode (render! #(select-workspace! (get-in workspace-modes [(:mode args) :native-id])))

    :take/previous (select-take! false)
    :take/next (select-take! true)
    :take/select (select-take-path! (:id args) (:path args))
    :take/audition (audition-take! (:id args) (:path args))
    :take/card
    (let [{:keys [op args]} (take-card-command @take-grid-cache (:slot args) (:revision args) (:audition args))]
      (dispatch-command! op args))
    :take/name (name-selected-take! (:name args))
    :take/mark-a (edit-take! :a)
    :take/compare (edit-take! :compare)
    :take/trim (trim-selected-take! (:from args) (:to args))
    :take/preferred (edit-take! :preferred)
    :take/publish (publish!)
    :take/recover (recover!)

    :record/dry (render! #(az/set-value! record-mode 1))
    :record/fx (render! #(az/set-value! record-mode 8))
    :record/start (schedule-passage! (render! #(az/value record-mode)) (:id args))
    :record/toggle (toggle-record-enabled!)
    :record/enable (render! #(az/set-value! record-enabled (if (:enabled args) 1 0)))
    :record/arm (arm-passage! (:id args) (:enabled args))
    :record/process
    (do
      (stop-input-check!)
      (stop-mix!)
      (start-take! true))

    :alert/dismiss (render! #(alert! ""))
    :playback/boost
    (render!
      #(do
         (az/set-value! audition-boost (:enabled args))
         (update-audition-gain!)))

    :routing/select (select-routing! args)
    :routing/check-input (check-input! (:enabled args))
    :routing/save (routing! :save)
    :routing/next (routing! :next)
    :routing/connect (routing! :connect)
    :routing/monitor (routing! :monitor)
    :routing/monitor-level (render! #(set-monitor-level! (:percent args)))

    ((:handler (get @extensions op)) args)))

(defn- execute-command! [{:keys [op args] :as command}]
  (validate-command! command)
  (when (and (contains? command :expected-revision)
             (not= (:expected-revision command) (:revision @project)))
    (throw (ex-info "Project changed; query before retrying this edit"
                    {:code :revision-conflict
                     :expected (:expected-revision command)
                     :actual (:revision @project)})))
  (when (and (or @session @armed)
             (not (contains? #{:transport/stop :view/routing :view/routing-tools :view/editor
                               :view/mode :view/zoom :view/pan :routing/monitor-level}
                             op)))
    (throw (ex-info "Recording owns the transport; stop it first"
                    {:code :recording-busy})))
  ;; A prepared mix is immutable. Stop it before editing the source selection,
  ;; so a different waveform is never displayed over old mix audio.
  (when (and @input-check
             (contains? #{:routing/select :routing/next :routing/connect :routing/monitor
                          :record/dry :record/fx} op))
    (throw (ex-info "Stop the input check before changing audio routing or capture mode."
                    {:code :input-check-busy})))
  (when (and (= "take" (namespace op))
             (not (contains? #{:take/select :take/audition :take/card} op)))
    (stop-mix!))
  (let [value (dispatch-command! op args)]
    {:op op
     :accepted? true
     :project-revision (:revision @project)
     :result value}))

(def ^:private ui-commands
  {1 :record/dry
   2 :transport/stop
   3 :record/process
   4 :take/previous
   5 :take/next
   6 :transport/toggle
   7 :take/publish
   8 :record/fx
   10 :take/mark-a
   11 :take/compare
   13 :take/preferred
   14 :take/recover
   20 :routing/save
   21 :routing/next
   22 :routing/connect
   23 :routing/monitor
   26 :transport/launch
   27 :project/undo
   28 :project/redo
   30 :mix/toggle
   34 :record/toggle
   35 :transport/audition})

(defn- ui-command [action]
  (case action
    39
    {:op :routing/check-input :args {:enabled (nil? @input-check)}}

    38
    {:op :record/start
     :args {:id (render! #(native-string (requested-recording-id)))}}

    (36 37)
    {:op :take/card
     :args (render! #(hash-map :slot (az/value take-grid-action-slot)
                               :revision (az/value take-grid-action-revision)
                               :audition (= action 37)))}

    (31 32 33)
    {:op :mix/loop
     :args (render! #(let [v (mixer/loop-state)
                           enabled (try
                                     (:enabled (az/value v))
                                     (finally
                                       (az/close! v)))]
                       {:from (if (= action 32)
                                (cursor-seconds)
                                (az/value loop-from))
                        :to (if (= action 33)
                              (cursor-seconds)
                              (az/value loop-to))
                        :enabled (if (= action 31)
                                   (not enabled)
                                   enabled)}))}
    9
    {:op :take/name
     :args {:name (native-string (render! #(entered-name)))}}

    12
    {:op :take/trim
     :args (render! #(hash-map :from (az/value trim-in)
                               :to (az/value trim-out)))}

    (when-let [op (get ui-commands action)]
      {:op op :args {}})))

(defn- process-command! [command ticket]
  ;; Command rejection is not an audio-device failure: never stop an existing capture here.

  (try
    (render! #(az/set-value! busy 1))
    (let [value (binding [*command-worker?* true]
                  (execute-command! command))]
      (if ticket
        (complete-request! ticket {:status :done :value value})
        (emit-event! {:type :command/completed
                      :source :ui
                      :op (:op command)
                      :status :done})))
    (catch Throwable e
      (if ticket
        (complete-request! ticket
                           {:status :error
                            :error (merge {:message (ex-message e)} (ex-data e))})
        (emit-event! {:type :command/completed
                      :source :ui
                      :op (:op command)
                      :status :error
                      :message (ex-message e)}))
      (warning! (ex-message e)))
    (finally
      (render!
        #(set-native-state! [busy (if (or @session @armed) 1 0)
                             capture-phase (cond
                                             (:device-interruption @session) 4
                                             @session 2
                                             @armed 1
                                             :else 0)])))))

(defn- worker-error-summary [error]
  ;; Compiler exceptions can contain whole generated modules. Keep diagnostics
  ;; inspectable without copying megabytes into the UI/control-plane snapshot.
  (let [causes (take-while some? (iterate ex-cause error))
        phase (some #(get (ex-data %) :aguafria/phase) causes)
        message (or (some-> (ex-message (last causes)) (str/split-lines) first)
                    (.getSimpleName (class error)))]
    {:phase phase :message (subs message 0 (min 512 (count message)))}))

(defn- handle-worker-error! [error]
  (let [summary (worker-error-summary error)
        compile-error? (= :zig-compile (:phase summary))]
    (swap! worker-health
           #(-> %
                (assoc :state (if compile-error? :waiting-for-code :error)
                       :error summary :last-error summary)
                (update :failures inc)))
    ;; A bad dev edit must not stop already-running native audio or discard PCM.
    ;; Do not call more native functions while their publication is failing.
    (when-not compile-error?
      ;; Cancel pending count-in even if device teardown itself fails.
      (reset! armed nil)
      (try
        (recorder/stop!)
        ;; Encoding/device failures keep unsaved PCM owned by the old session.
        (render! #(do
                    (az/set-value! busy (if @session 1 0))
                    (az/set-value! capture-phase (cond
                                                   (:device-interruption @session) 4
                                                   @session 2
                                                   :else 0))))
        (warning! (:message summary))
        (catch InterruptedException interrupted (throw interrupted))
        (catch Throwable cleanup-error
          (swap! worker-health assoc :cleanup-error (worker-error-summary cleanup-error)))))))

(defn- worker-iteration! [refresh?]
  (check-playback-devices!)
  (check-audio-devices!)
  (expire-input-check!)
  ;; Include take-action! in the boundary: resolving this native accessor may
  ;; fail during compilation, before a command has even been consumed.
  (let [action (take-action!)]
    (if (pos? action)
      (when-let [command (ui-command action)]
        (process-command! command nil))
      (when-let [ticket (.poll command-queue)]
        (process-command! (:command ticket) ticket))))
  (when-let [{:keys [until action id]} @armed]
    (when (>= (System/currentTimeMillis) until)
      (select-recording-target! id)
      (reset! armed nil)
      (if (= action 8)
        (start-live-take!)
        (start-take! false))))
  (checkpoint! false)
  (when (and @session
             (not (:device-interruption @session))
             (recorder/done?)
             (render! #(busy?)))
    (save-take!)
    (render! #(do
                (az/set-value! busy 0)
                (az/set-value! capture-phase 0))))
  (when refresh?
    (refresh-display!)))

(defn- worker-cycle! [refresh?]
  (try
    (worker-iteration! refresh?)
    (when (not= :running (:state @worker-health))
      (swap! worker-health #(-> % (assoc :state :running) (dissoc :error :cleanup-error))))
    (catch InterruptedException interrupted (throw interrupted))
    (catch Throwable error
      (handle-worker-error! error))))

(defn restart-worker!
  "Start a stopped Studio worker without reopening windows, engines or takes.
  Refuse to replace a live worker; consumed commands are never replayed."
  []
  (locking worker
    (when (and @worker (not (future-done? @worker)))
      (throw (ex-info "Studio worker is still running" {:code :worker-running})))
    (when-not (render! #(az/value attached))
      (throw (ex-info "Open the studio window first" {:code :closed})))
    (swap! worker-health assoc :state :running)
    (reset! worker
            (future
              (try
                (loop [display-tick 0]
                  (worker-cycle! (zero? display-tick))
                  (Thread/sleep 40)
                  (recur (mod (inc display-tick) 6)))
                (catch InterruptedException _
                  (swap! worker-health assoc :state :closed))))))
  :started)

(defn open! []
  (when @worker
    (throw (ex-info "Studio is already open." {})))
  (reset! project (files/load-project! project-file index-file))
  (reset! takes (:takes @project))
  (reset! presets (files/read-state presets-file {}))
  (reset! display-cache nil)
  (reset! clip-display-cache nil)
  (when-not (recorder/initialize!)
    (throw (ex-info "Audio device enumeration failed" {})))
  (render! #(initialize-listen-output!))
  (doseq [[capture? field total] [[true return-input (az/value recorder/capture-count)]
                                  [false effects-output (az/value recorder/playback-count)]]
          index (range total)
          :when (= "BlackHole 16ch" (native-string (recorder/device-name capture? index)))]
    (render! #(az/set-value! field index)))
  (render! #(attach!))
  (when-not (render! #(az/value attached))
    (throw (ex-info "Studio window could not open" {})))
  (try (restore-window!)
       (catch Throwable e (warning! (str "Window settings not restored: " (ex-message e)))))
  (reset! window-save-state nil)
  (render! #(az/set-value! page 0))
  (restart-worker!)
  :opened)

(defn close! []
  (when @session
    (throw (ex-info "Stop and save the take before closing the studio." {})))
  (save-window! true)
  (stop-mix!)
  (when-let [f @worker]
    (future-cancel f)
    (reset! worker nil)
    (swap! worker-health assoc :state :closed))
  (locking command-queue
    (loop []
      (when-let [ticket (.poll command-queue)]
        (complete-request! ticket {:status :error :error {:code :closed :message "Studio closed before execution"}})
        (recur))))
  (reset! armed nil)
  (recorder/stop!)
  (reset! input-check nil)
  (render! #(do (detach!) (az/set-value! busy 0) (az/set-value! capture-phase 0)))
  :closed)
