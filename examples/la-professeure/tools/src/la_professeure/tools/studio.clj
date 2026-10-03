(ns la-professeure.tools.studio
  "Native Vulkan recording workspace, attached to the game's existing render thread."
  (:require [aguafria.c :as ac]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.std.unicode :as unicode]
            [aguafria.keyword :as k]
            [aguafria.zig :as a]
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
            [la-professeure.build :as build]
            [la-professeure.dialogue :as dialogue]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files StandardCopyOption CopyOption]))

(let [{:keys [include library]} (build/studio-text!)
      configuration (a/configuration)]
  (a/configure!
   {:module-zig-args (update (:module-zig-args configuration)
                             "la-professeure.tools.studio"
                             #(cond-> (vec %)
                                (not (some #{include} %)) (conj include)))
     ;; Repeated flags such as -framework are meaningful argument pairs.
    :zig-args (cond-> (vec (:zig-args configuration))
                (not (some #{library} (:zig-args configuration))) (conj library))}))

(a/defconst text-api
  (k/import (a/clj! (ac/import! "la_professeure_utf8proc"
                                 (io/file (build/root) "build/vendor/utf8proc/utf8proc.h")
                                 {:args ["-lc"]}))))

(a/defconst grapheme-break? (:utf8proc_grapheme_break_stateful text-api))

(let [{:keys [include library]} (build/studio-gestures!)
      configuration (a/configuration)]
  (a/configure!
   {:module-zig-args (update (:module-zig-args configuration)
                             "la-professeure.tools.studio"
                             #(cond-> (vec %) (not (some #{include} %)) (conj include)))
    :zig-args (cond-> (vec (:zig-args configuration))
                (not (some #{library} (:zig-args configuration))) (conj library))}))

(a/defconst gestures-api
  (k/import (a/clj! (ac/import! "la_professeure_gestures"
                                 (io/file (build/root) "tools/native/studio_gestures.h")
                                 {:args ["-lc"]}))))

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
  (state-assignment-forms 'aguafria.keyword/= bindings))

(defmacro ^:private set-native-state!
  "JVM counterpart of set-state!: ordered writes to native Vars. Use inside a
  render! callback; this macro does not schedule work or provide atomicity."
  [bindings]
  (state-assignment-forms 'aguafria.zig/set-value! bindings))

(a/defvar selected :u32 1)

(a/defvar studio-window [:optional [:* glfw/GLFWwindow]] k/null)

(a/defvar attached :bool false)

(a/defvar observed-shaders :u64 0)

(a/defvar renderer gpu/RendererContext (mem/zeroes gpu/RendererContext))

(a/defvar voices [:array 2 audio/ma_sound] (mem/zeroes [:array 2 audio/ma_sound]))

(a/defvar voice-decoders [:array 2 audio/ma_decoder] (mem/zeroes [:array 2 audio/ma_decoder]))

(a/defvar voice-slot :usize 0)

(a/defvar voice-ready :bool false)

(a/defvar playback-engine audio/ma_engine (mem/zeroes audio/ma_engine))

(a/defvar playback-ready :bool false)

(a/defvar playback-interrupted :bool false)

(a/defvar playback-output :u32 4294967295)

(a/defvar headphones :u32 4294967295)

(a/defvar playback-level :u32 0)

(a/defvar playback-peak :u32 0)

(a/defvar playback-signal-frames :u64 0)

(a/defvar audition-boost :bool false)

(a/defvar audition-source-peak :f32 1.0)

(a/defvar audition-gain :f32 1.0)

(a/defn update-audition-gain! :void []
  (k/= audition-gain (if (and audition-boost (k/> audition-source-peak 0.000001))
                       (k/max 1.0 (k/min 1000.0 (k// 0.2 audition-source-peak)))
                       1.0))
  (when voice-ready
    (audio/ma_sound_set_volume (k/& (a/get voices voice-slot)) (k/* 0.8 audition-gain))))

(a/defn playback-peak-value :u32 []
  (k/atomicLoad :u32 (k/& playback-peak) :.acquire))

(a/defn playback-signal-count :u64 []
  (k/atomicLoad :u64 (k/& playback-signal-frames) :.acquire))

(a/defn playback-process! :void {:zig/qualifiers "callconv(.c)"}
  [[user [:optional [:* :anyopaque]]] [output [:c-pointer :f32]] [frames :u64]]
  (k/= :_ user)
  (let [peak (k/var (k/f32 0.0))]
    (when (k/!= output k/null)
      (dotimes [i (k/* frames 2)]
        (k/= peak (k/max peak (k/abs (a/get output i))))))
    (let [level (k/as (k/intFromFloat (k/* 1000000.0 (k/min 1.0 peak))) :u32)]
      (k/atomicStore :u32 (k/& playback-level) level :.release)
      (when (k/> level (k/atomicLoad :u32 (k/& playback-peak) :.acquire))
        (k/atomicStore :u32 (k/& playback-peak) level :.release))
      (when (k/> level 0)
        (k/= :_ (k/atomicRmw :u64 (k/& playback-signal-frames) :.Add frames :.monotonic))))))

(a/defvar frame-cursor :f32 0.0)

(a/defvar framebuffer-scale :f32 1.0)

(a/defvar route-menu :u32 0)

(a/defvar route-offset :u32 0)

(a/defvar route-focus :u32 0)

(a/defvar output-mute-index :u32 4294967295)

(a/defvar output-mute-state :i32 -1)

(a/defvar output-mute-checked-at :f64 -10.0)

(a/defn selected-output-mute :i32 []
  (if (and (k/== output-mute-index headphones)
           (k/< (k/- (glfw/glfwGetTime) output-mute-checked-at) 2.0))
    output-mute-state
    -1))

(a/defn update-output-mute! :void [[index :u32] [state :i32]]
  (when (k/== index headphones)
    (set-state! [output-mute-index index
                 output-mute-state state
                 output-mute-checked-at (glfw/glfwGetTime)])))

(a/defn output-device-uid [:slice-const :u8] [[index :u32]]
  (when (or (k/! recorder/initialized)
            (k/>= index recorder/playback-count))
    (k/return ""))
  (let [entry
        (k/as (k/ptrCast
               (a/unwrap
                (k/as (k/& (a/get recorder/playback-info index))
                      [:c-pointer recorder/DeviceInfo]))) [:* recorder/DeviceInfo])
        uid (:coreaudio (:id entry))
        length (k/var (k/usize 0))]
    (k/while (and (k/< length 256) (k/!= (a/get uid length) 0))
      (k/= length (k/+ length 1)))
    ;; Return the context-owned UID, not the temporary local array copy.
    (a/slice (:coreaudio (:id entry)) 0 length)))

(a/defn read-output-mute :i32 [[uid [:slice-const :u8]]]
  ;; Invoked on the control worker, never in a render/audio callback.
  (when (or (k/== (:len uid) 0) (k/>= (:len uid) 256))
    (k/return -1))
  (let [text (k/var (k/as (mem/zeroes [:array 256 :u8]) [:array 256 :u8]))]
    (k/memcpy (a/slice text 0 (:len uid)) uid)
    ((:lp_studio_output_mute gestures-api) (k/& text))))

(a/defn route-total :u32 []
  (if (or (k/== route-menu 1) (k/== route-menu 3))
    recorder/capture-count
    recorder/playback-count))

(a/defvar microphone :u32 0)

(a/defvar return-input :u32 0)

(a/defvar effects-output :u32 0)

(a/defn route-selected :u32 []
  (cond
    (k/== route-menu 1)
    microphone
    (k/== route-menu 2)
    effects-output
    (k/== route-menu 3)
    return-input
    :else
    headphones))

(a/defvar route-click :bool false)

(a/defvar clicked false)

(a/defvar name-focus false)

(a/defvar name-drag false)

(a/defvar trim-drag :u32 0)

(a/defn- cancel-name-composition! :void []
  (when (k/!= studio-window k/null)
    ((:lp_studio_ime_cancel gestures-api)
     ((:glfwGetCocoaWindow gestures-api) (k/ptrCast studio-window)))))

(a/defvar busy :u8 0)

(a/defn busy? :bool []
  (k/!= (k/atomicLoad :u8 (k/& busy) :.acquire) 0))

(a/defn route-open! :void [[menu :u32]]
  (when (or (busy?)
            (recorder/input-check-active?)
            (k/< menu 1)
            (k/> menu 4))
    (k/return))
  (when name-focus
    (cancel-name-composition!))
  (set-state! [route-menu menu
               route-focus (k/min (route-selected) (k/- (k/max 1 (route-total)) 1))
               route-offset (k/* (k// route-focus 8) 8)
               name-focus false
               name-drag false
               trim-drag 0
               clicked false
               route-click false]))

(a/defn route-move! :void [[down? :bool]]
  (if down?
    (k/= route-focus (k/min (k/+ route-focus 1) (k/- (k/max 1 (route-total)) 1)))
    (k/= route-focus (k/- route-focus (k/min route-focus 1))))
  (k/= route-offset (k/* (k// route-focus 8) 8)))

(a/defn stop-voice! :void []
  (when voice-ready
    (audio/ma_sound_uninit (k/& (a/get voices voice-slot)))
    (k/= :_ (audio/ma_decoder_uninit (k/& (a/get voice-decoders voice-slot))))
    (k/= voice-ready false)))

(a/defn close-playback! :void []
  (stop-voice!)
  (when playback-ready
    (audio/ma_engine_uninit (k/& playback-engine))
    (set-state! [playback-ready false
                 playback-interrupted false
                 playback-output 4294967295])))

(a/defvar preview-paused :bool false)

(a/defn route-select! :void [[index :u32]]
  (when (or (busy?)
            (recorder/input-check-active?)
            (k/== route-menu 0)
            (k/>= index (route-total)))
    (k/return))
  (cond
    (k/== route-menu 1)
    (k/= microphone index)
    (k/== route-menu 2)
    (k/= effects-output index)
    (k/== route-menu 3)
    (k/= return-input index)
    (k/== route-menu 4)
    (when (k/!= headphones index)
      (close-playback!)
      (mixer/close!)
      (set-state! [preview-paused false
                   output-mute-index 4294967295
                   headphones index])))
  (k/= route-menu 0))

(a/defn game-audio-suppressed? :bool []
  scene/studio-audio-suppressed)

;; Only game's sounds are gated; the studio owns its selected playback device.
;; Preserve the user's M-key mute independently of temporary studio focus.
(a/defn suppress-game-audio! :void [[suppressed :bool]]
  (when (k/== scene/studio-audio-suppressed suppressed)
    (k/return))
  (k/= scene/studio-audio-suppressed suppressed)
  (when scene/voice-ready
    (audio/ma_sound_set_volume (k/& (a/get scene/voices scene/voice-slot))
                               (if (or scene/audio-muted suppressed)
                                 0.0
                                 0.8)))
  (when scene/audio-ready
    (audio/ma_sound_set_volume (k/& (a/get scene/tracks scene/active-track))
                               (if (or scene/audio-muted suppressed)
                                 0.0
                                 0.35))))

(a/defn initialize-listen-output! :void []
  ;; Never silently send first-run audition into the effects loopback.
  ;; An explicit user selection is preserved, including a virtual output.

  (when (k/!= headphones 4294967295)
    (k/return))
  (dotimes [i recorder/playback-count]
    (let [name (recorder/device-name false (k/intCast i))]
      (when (and (k/== (mem/indexOf :u8 name "BlackHole") k/null)
                 (k/== (mem/indexOf :u8 name "Aggregate") k/null))
        (when (or (k/== headphones 4294967295)
                  (k/!= (:isDefault (a/get recorder/playback-info i)) 0))
          (k/= headphones (k/intCast i)))))))

(a/defn prepare-playback! :bool []
  (initialize-listen-output!)
  (when (or (k/! recorder/initialized)
            (k/>= headphones recorder/playback-count))
    (k/return false))
  (when (and playback-ready (k/== playback-output headphones))
    (when playback-interrupted
      (when (k/!= ((:ma_engine_start recorder/api) (k/ptrCast (k/& playback-engine))) 0)
        (k/return false))
      (k/= playback-interrupted false))
    (k/return true))
  (close-playback!)
  (let [config (k/var ((:ma_engine_config_init recorder/api)))]
    (k/= (:pContext config) (k/& recorder/context))
    (k/= (:pPlaybackDeviceID config) (k/& (:id (a/get recorder/playback-info headphones))))
    (k/= (:channels config) 2)
    (k/= (:sampleRate config) 48000)
    (k/= (:onProcess config) (k/& playback-process!))
    (when (k/!= ((:ma_engine_init recorder/api) (k/& config) (k/ptrCast (k/& playback-engine))) 0)
      (k/return false)))
  (set-state! [playback-ready true
               playback-output headphones])
  true)

(a/defn play-voice-file! :bool [[path [:slice-const :u8]]]
  (when (or (k/! (prepare-playback!))
            (k/== (:len path) 0)
            (k/>= (:len path) 4096))
    (k/return false))
  (let [filename (k/var (mem/zeroes [:array 4096 :u8]))
        next (k/mod (k/+ voice-slot 1) 2)
        decoder (k/& (a/get voice-decoders next))
        candidate (k/& (a/get voices next))]
    (dotimes [i (:len path)]
      (when (k/== (a/get path i) 0)
        (k/return false)))
    (k/memcpy (a/slice filename 0 (:len path)) path)
    (when (k/!= (audio/ma_decoder_init_file (k/& filename) k/null decoder) audio/MA_SUCCESS)
      (k/return false))
    (when (k/!= (audio/ma_sound_init_from_data_source (k/& playback-engine)
                                                      (k/as (k/ptrCast decoder) [:* audio/ma_data_source])
                                                      0 k/null candidate) audio/MA_SUCCESS)
      (k/= :_ (audio/ma_decoder_uninit decoder))
      (k/return false))
    (update-audition-gain!)
    (audio/ma_sound_set_looping candidate 0)
    (audio/ma_sound_set_volume candidate (k/* 0.8 audition-gain))
    (k/atomicStore :u32 (k/& playback-peak) 0 :.release)
    (k/atomicStore :u64 (k/& playback-signal-frames) 0 :.release)
    (when (k/!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate)
      (k/= :_ (audio/ma_decoder_uninit decoder))
      (k/return false))
    (stop-voice!)
    (set-state! [voice-slot next
                 voice-ready true])
    true))

(a/defvar scroll :f32 0.0)

(a/defvar focus-scroll :f32 0.0)

(a/defvar content-height :f32 0.0)

(a/defvar focus-height :f32 0.0)

(a/defvar mouse-x :f64 0.0)

(a/defvar mouse-y :f64 0.0)

(a/defvar mouse-down false)

(a/defvar monitor-level-drag :bool false)

(a/defvar pending :u32 0)

(a/defvar capture-phase :u8 0)

(a/defn capture-phase-value :u8 []
  (if (and (k/== capture-phase 2) (recorder/tail-active?))
    3
    capture-phase))

(a/defvar record-enabled :u8 0)

(a/defvar record-track :u32 4294967295)

(a/defvar capture-id-text [:array 64 :u8] (mem/zeroes [:array 64 :u8]))

(a/defvar capture-id-length :usize 0)

;; A passage can use the whole story text capacity. Never truncate a recording
;; script or keep a pointer into the story's hot-swapped backing storage.
(a/defvar capture-script-text [:array 262144 :u8] (mem/zeroes [:array 262144 :u8]))

(a/defvar capture-script-length :usize 0)

(a/defn capture-script! :bool
  [[id [:slice-const :u8]] [text [:slice-const :u8]]]
  (when (or (k/== (:len id) 0)
            (k/> (:len id) 64)
            (k/> (:len text) 262144))
    (k/return false))
  (k/memcpy (a/slice capture-id-text 0 (:len id)) id)
  (k/memcpy (a/slice capture-script-text 0 (:len text)) text)
  (set-state! [capture-id-length (:len id)
               capture-script-length (:len text)])
  true)

(a/defn captured-id [:slice-const :u8] []
  (a/slice capture-id-text 0 capture-id-length))

(a/defn node-id [:slice-const :u8] [[index :u32]]
  (let [node (k/& (a/get (:nodes (a/get scene/stories scene/active-story)) index))]
    (a/slice (:id node) 0 (:id_len node))))

(a/defn capture-passage? :bool [[index :u32]]
  (and (k/> capture-phase 0)
       (k/> capture-id-length 0)
       (k/< index scene/passage-entity-count)
       (mem/eql :u8 (node-id index) (captured-id))))

(a/defvar workspace-mode :u32 0)

(a/defn showing-capture-script? :bool []
  (and (k/== workspace-mode 1)
       (k/> capture-phase 0)
       (k/> capture-id-length 0)))

(a/defn waveform-passage-id [:slice-const :u8] []
  (if (showing-capture-script?)
    (captured-id)
    (node-id selected)))

(a/defvar record-request-text [:array 65 :u8] (mem/zeroes [:array 65 :u8]))

(a/defvar record-request-length :usize 0)

(a/defvar record-mode :u8 1)

(a/defn capture-fx? :bool []
  ;; The configured next recording is not the active operation. An offline
  ;; effects pass can process a saved take while the next-recording mode is Dry.
  (and (k/>= capture-phase 2)
       (k/>= recorder/mode 2)))

(a/defn effects-pass? :bool []
  (and (k/>= capture-phase 2)
       (k/== recorder/mode 2)))

(a/defn record-route-label [:slice-const :u8] []
  (cond
    (effects-pass?) "FX pass"
    (capture-fx?) "Live FX"
    (k/>= capture-phase 2) "Dry"
    (k/== record-mode 8) "Live FX"
    :else "Dry"))

(a/defvar countdown-until :f64 0.0)

(a/defvar capture-cursor :f32 0.0)

(a/defvar status-text [:array 256 :u8] (mem/zeroes [:array 256 :u8]))

(a/defvar status-length :usize 0)

(a/defvar alert-text [:array 256 :u8] (mem/zeroes [:array 256 :u8]))

(a/defvar alert-length :usize 0)

(a/defvar input-meter-text [:array 64 :u8] (mem/zeroes [:array 64 :u8]))

(a/defvar input-meter-length :usize 0)

(a/defvar return-meter-text [:array 64 :u8] (mem/zeroes [:array 64 :u8]))

(a/defvar return-meter-length :usize 0)

(a/defvar test-click false)

(a/defvar test-x :f64 0.0)

(a/defvar test-y :f64 0.0)

(a/defvar page :u32 0)

(a/defvar workspace-record-prior :u8 0)

(a/defstruct TakeCard
  [[available :bool]
   [missing :bool]
   [chosen :bool]
   [seconds :f32]
   [label-length :usize]
   [label [:array 128 :u8]]
   [bins [:array 32 :f32]]])

(a/defvar take-cards [:array 48 TakeCard] (mem/zeroes [:array 48 TakeCard]))

(a/defvar take-grid-offset :u32 4294967295)

(a/defvar take-grid-rows :u32 0)

(a/defvar take-grid-revision :u64 0)

(a/defvar take-grid-action-slot :u32 0)

(a/defvar take-grid-action-revision :u64 0)

(a/defvar record-scroll :f32 0.0)

(a/defvar record-text-height :f32 0.0)

(a/defvar tail-seconds :u32 1)

(a/defvar countdown-seconds :u32 0)

(a/defn begin-countdown-clock! :void []
  (k/= countdown-until (k/+ (glfw/glfwGetTime) (k/as (k/floatFromInt countdown-seconds) :f64))))

(a/defvar monitor-enabled :u8 0)

(a/defvar compensate :u8 1)

(a/defvar trim-in :u32 0)

(a/defvar trim-out :u32 100)

(a/defn name-focused? :bool
  "Typed host boundary: native state storage is not a Clojure truth value." []
  name-focus)

(a/defvar back-down false)

(a/defvar edit-name [:array 128 :u8] (mem/zeroes [:array 128 :u8]))

(a/defvar name-length :usize 0)

(a/defvar name-caret :usize 0)

(a/defvar name-anchor :usize 0)

(a/defvar name-view :usize 0)

(a/defvar name-drag-elapsed :f64 0.0)

(a/defvar name-pointer-time :f64 0.0)

(a/defstruct NameDraft {:layout :extern}
              [[text [:array 128 :u8]] [length :usize] [caret :usize] [anchor :usize] [view :usize]])

(a/defvar name-history [:array 33 NameDraft] (mem/zeroes [:array 33 NameDraft]))

(a/defvar name-history-position :usize 0)

(a/defvar name-history-end :usize 0)

(a/defvar name-batch :bool false)

(a/defvar name-batch-recorded :bool false)

(a/defn name-draft NameDraft []
  (NameDraft {:text edit-name :length name-length :caret name-caret :anchor name-anchor :view name-view}))

(a/defn name-restore! :void [[draft NameDraft]]
  (set-state! [edit-name (:text draft)
               name-length (:length draft)
               name-caret (:caret draft)
               name-anchor (:anchor draft)
               name-view (:view draft)]))

(a/defn name-checkpoint! :void []
  ;; A paste is one edit; draft history never enters the project undo journal.

  (when (and name-batch name-batch-recorded)
    (k/return))
  (when name-batch
    (k/= name-batch-recorded true))
  (when (k/== name-history-position 32)
    (dotimes [i 31]
      (k/= (a/get name-history i) (a/get name-history (k/+ i 1))))
    (k/= name-history-position 31))
  (k/= (a/get name-history name-history-position) (name-draft))
  (set-state! [name-history-position (k/+ name-history-position 1)
               name-history-end name-history-position]))

(a/defn name-undo! :void [[redo? :bool]]
  (when (or (busy?)
            (if redo?
              (k/>= name-history-position name-history-end)
              (k/== name-history-position 0)))
    (k/return))
  (k/= (a/get name-history name-history-position) (name-draft))
  (k/= name-history-position (if redo?
                               (k/+ name-history-position 1)
                               (k/- name-history-position 1)))
  (name-restore! (a/get name-history name-history-position)))

(a/defn text-boundary :usize
  "UTF-8 byte offset at the previous/next extended grapheme boundary.
  Scan in order: RI pairs and joined emoji need state from preceding characters." [[text [:slice-const :u8]] [position :usize] [previous? :bool]]
  (let [limit (k/min position (:len text))
        offset (k/var (k/usize 0))
        boundary (k/var (k/usize 0))
        previous (k/var (k/i32 0))
        state (k/var (k/i32 0))]
    (k/while (k/< offset (:len text))
      (let [bytes (catch (unicode/utf8ByteSequenceLength (a/get text offset))
                         (k/return boundary))]
        (when (k/> (k/+ offset bytes) (:len text))
          (k/return boundary))
        (let [cp (catch (unicode/utf8Decode (a/slice text offset (k/+ offset bytes)))
                        (k/return boundary))]
          (when (and (k/> offset 0)
                     (grapheme-break? previous (k/intCast cp) (k/& state)))
            (when (if previous? (k/>= offset limit) (k/> offset limit))
              (k/return (if previous? boundary offset)))
            (k/= boundary offset))
          (set-state! [previous (k/intCast cp)
                       offset (k/+ offset bytes)]))))
    (if previous? boundary (:len text))))

(a/defn name-previous :usize [[position :usize]]
  (text-boundary (a/slice edit-name 0 name-length) position true))

(a/defn name-next :usize [[position :usize]]
  (text-boundary (a/slice edit-name 0 name-length) position false))

(a/defn name-settle-caret! :void []
  ;; An insertion/deletion can join the neighboring characters (e.g. RI pairs).
  (when (k/> name-caret 0)
    (k/= name-caret (name-next (k/- name-caret 1))))
  (k/= name-anchor name-caret)
  (k/= name-view (text-boundary (a/slice edit-name 0 name-length)
                                (k/+ (k/min name-view name-caret) 1) true)))

(a/defn text-prefix :usize
  "Largest complete grapheme prefix fitting a UTF-8 byte budget." [[text [:slice-const :u8]] [budget :usize]]
  (let [end (k/var (k/usize 0))]
    (k/while (k/< end (:len text))
      (let [next (text-boundary text end false)]
        (when (or (k/<= next end) (k/> next budget))
          (k/break))
        (k/= end next)))
    end))

(a/defn name-move! :void [[position :usize] [extend? :bool]]
  (k/= name-caret (k/min position name-length))
  (when (k/! extend?)
    (k/= name-anchor name-caret)))

(a/defn name-delete! :void []
  (let [a (k/min name-caret name-anchor)
        b (k/max name-caret name-anchor)]
    (dotimes [i (k/- name-length b)]
      (k/= (a/get edit-name (k/+ a i)) (a/get edit-name (k/+ b i))))
    (set-state! [name-length (k/- name-length (k/- b a))
                 name-caret a
                 name-anchor a
                 name-view (k/min name-view a)])))

(a/defvar details [:array 512 :u8] (mem/zeroes [:array 512 :u8]))

(a/defvar details-length :usize 0)

(a/defvar wave [:array 128 :f32] (mem/zeroes [:array 128 :f32]))

(a/defvar waveform-owner [:array 64 :u8] (mem/zeroes [:array 64 :u8]))

(a/defvar waveform-owner-length :usize 0)

(a/defvar waveform-uploaded :bool false)

(a/defvar track-offset :u32 0)

(a/defvar track-snapshot :u32 4294967295)

(a/defstruct ClipViewportRow {:layout :extern}
              [[seconds :f32] [start :f32] [count :u32] [wave [:array 128 :f32]]])

(a/defvar clip-viewport [:array 32 ClipViewportRow]
  (mem/zeroes [:array 32 ClipViewportRow]))

(a/defvar track-snapshot-count :u32 0)

(a/defvar clip-upload-revision :u64 0)

(a/defvar take-seconds :f32 0.0)

(a/defconst comparison-unmarked :u8 0)

(a/defconst comparison-select-b :u8 1)

(a/defconst comparison-listen-a :u8 2)

(a/defconst comparison-listen-b :u8 3)

(a/defconst comparison-missing-a :u8 4)

(a/defconst comparison-missing-b :u8 5)

(a/defvar comparison-owner [:array 64 :u8] (mem/zeroes [:array 64 :u8]))

(a/defvar comparison-owner-length :usize 0)

(a/defvar comparison-code :u8 comparison-unmarked)

(a/defn comparison-view! :void [[id [:slice-const :u8]] [code :u8]]
  (k/= comparison-owner-length (k/min 64 (:len id)))
  (k/memcpy (a/slice comparison-owner 0 comparison-owner-length)
            (a/slice id 0 comparison-owner-length))
  (k/= comparison-code code))

(a/defn selected-id [:slice-const :u8] []
  (let [node (k/& (a/get (:nodes (a/get scene/stories scene/active-story)) selected))]
    (a/slice (:id node) 0 (:id_len node))))

(a/defn selected-comparison-code :u8 []
  (if (mem/eql :u8
               (selected-id)
               (a/slice comparison-owner 0 comparison-owner-length))
    comparison-code
    comparison-unmarked))

(a/defn selected-waveform? :bool []
  (and waveform-uploaded
       (or (showing-capture-script?) (k/< selected scene/passage-entity-count))
       (mem/eql :u8 (waveform-passage-id)
                (a/slice waveform-owner 0 waveform-owner-length))))

(a/defn selected-take-seconds :f32 []
  (if (selected-waveform?) take-seconds 0.0))

(a/defn take-editable? :bool []
  (and (k/! (busy?))
       (k/> (selected-take-seconds) 0.0)))

(a/defn comparison-enabled? :bool []
  (let [code (selected-comparison-code)]
    (and (take-editable?)
         (or (k/== code comparison-listen-a)
             (k/== code comparison-listen-b)))))

(a/defn comparison-label [:slice-const :u8] []
  (let [code (selected-comparison-code)]
    (cond
      (k/== code comparison-select-b) "A marked: select B"
      (k/== code comparison-listen-a) "A/B: listen A"
      (k/== code comparison-listen-b) "A/B: listen B"
      (k/== code comparison-missing-a) "A unavailable"
      (k/== code comparison-missing-b) "B unavailable"
      :else "Compare A/B")))

(a/defn comparison-hint [:slice-const :u8] []
  (let [code (selected-comparison-code)]
    (cond
      (k/! (take-editable?)) "Select a loaded take before comparing."
      (k/== code comparison-select-b) "A is marked. Select a different take for this passage as B."
      (k/== code comparison-missing-a) "Take A is missing. Select an available take and mark A again."
      (k/== code comparison-missing-b) "Take B is missing. Select an available take."
      :else "Mark take A for this passage, then select a different take as B.")))

(a/defvar timeline-seconds :f32 30.0)

(a/defvar timeline-start :f32 0.0)

(a/defvar seek-seconds :f32 0.0)

(a/defvar preview-node :u32 4294967295)

(a/defvar mix-mode :bool false)

(a/defvar loop-from :f32 0.0)

(a/defvar loop-to :f32 0.0)

(a/defvar follow-playhead :bool true)

(a/defvar track-scroll :f32 0.0)

(a/defvar bar-grab :f32 0.0)

(a/defvar event-pressed false)

(a/defvar event-double false)

(a/defvar double-clicked false)

(a/defvar press-x :f64 0.0)

(a/defvar press-y :f64 0.0)

(a/defvar last-press-time :f64 -10.0)

(a/defvar last-press-x :f64 -100.0)

(a/defvar last-press-y :f64 -100.0)

(a/defvar callbacks-installed false)

(a/defvar previous-scroll glfw/GLFWscrollfun k/null)

(a/defvar previous-key glfw/GLFWkeyfun k/null)

(a/defvar previous-mouse glfw/GLFWmousebuttonfun k/null)

(a/defvar previous-char glfw/GLFWcharfun k/null)

(a/defvar hand-cursor [:optional [:* glfw/GLFWcursor]] k/null)

(a/defvar resize-cursor [:optional [:* glfw/GLFWcursor]] k/null)

(a/defvar vertical-cursor [:optional [:* glfw/GLFWcursor]] k/null)

(a/defvar text-cursor [:optional [:* glfw/GLFWcursor]] k/null)

(a/defvar hint-text [:array 256 :u8] (mem/zeroes [:array 256 :u8]))

(a/defvar hint-length :usize 0)

(a/defvar rendered-frames :u64 0)

;; One logical-point layout for drawing AND hit testing. The left track headers
;; and right routing column keep their widths; extra space belongs to the editor.
(a/defvar window-width :f32 1100.0)

(a/defvar window-height :f32 760.0)

(a/defvar routing-visible :bool true)

(a/defvar editor-top :f32 444.0)

(a/defvar divider-drag :bool false)

(a/defvar divider-grab :f32 0.0)

(a/defn right-x :f32 [[base :f32]]
  (k/+ base (k/- window-width 1100.0)))

(a/defn routing-space :f32 []
  (if routing-visible
    0.0
    202.0))

(a/defn content-x :f32 [[base :f32]]
  (k/+ (right-x base) (routing-space)))

(a/defn bottom-y :f32 [[base :f32]]
  (k/+ base (k/- window-height 760.0)))

(a/defn timeline-width :f32 []
  (k/+ (k/- window-width 458.0) (routing-space)))

(a/defn timeline-center :f32 []
  (k/+ 242.0 (k// (timeline-width) 2.0)))

(a/defn main-width :f32 []
  (k/+ (k/- window-width 232.0) (routing-space)))

(a/defn show-routing! :void [[visible :bool]]
  ;; Visibility is not device/monitor state. Dismiss the popup and any old drag.

  (set-state! [routing-visible visible
               route-menu 0
               route-click false
               trim-drag 0
               divider-drag false
               monitor-level-drag false
               clicked false]))

(a/defn editor-y :f32 [[base :f32]]
  (k/+ base (k/- editor-top 444.0)))

(a/defn track-height :f32 []
  (k/- editor-top 182.0))

(a/defn visible-row-count :u32 []
  (if (k/== workspace-mode 2)
    (k/intFromFloat (k/max 2.0 (k/min 16.0 (k// (k/- editor-top 176.0) 62.0))))
    (k/intFromFloat (k/max 2.0 (k/min 32.0 (k// (k/+ (track-height) 2.0) 56.0))))))

(a/defn set-editor-top! :void [[top :f32]]
  (set-state! [editor-top (k/max 316.0 (k/min (k/min 1972.0 (k/- window-height 300.0)) top))
               track-offset (k/min track-offset (k/- (k/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))]))

(a/defn editor-text-height :f32 []
  (k/- window-height editor-top 220.0))

(a/defn record-pane-height :f32 []
  (k/- window-height 535.0))

(a/defn record-row-count :u32 []
  (k/intFromFloat (k/max 3.0 (k/min 32.0 (k// (k/- window-height 292.0) 54.0)))))

(a/defn select-workspace! :void [[mode :u32]]
  ;; Record mode temporarily enables REC, not capture. Re-selecting the current
  ;; mode must not overwrite the saved preference or a user's manual REC toggle.

  (when (k/> mode 2)
    (k/return))
  (when (k/!= workspace-mode mode)
    (when (k/== workspace-mode 1)
      (k/= record-enabled workspace-record-prior))
    (when (k/== mode 1)
      (set-state! [workspace-record-prior record-enabled
                   record-enabled 1])))
  ;; Arm state, audio devices, cursor and capture ownership remain untouched.
  (when name-focus
    (cancel-name-composition!))
  (set-state! [workspace-mode mode
               trim-drag 0
               divider-drag false
               name-focus false
               name-drag false
               route-menu 0
               clicked false]))

(a/defn set-workspace-mode! :void [[record? :bool]]
  ;; Preserve the existing two-mode entry point for REPL clients.
  (select-workspace! (if record? 1 0)))

(a/defn update-layout! :void []
  (let [width (k/var (k/as 0 :c_int))
        height (k/var (k/as 0 :c_int))]
    (glfw/glfwGetWindowSize studio-window (k/& width) (k/& height))
    (when (and (k/> width 0) (k/> height 0))
      (set-state! [window-width (k/floatFromInt width)
                   window-height (k/floatFromInt height)])
      (set-editor-top! editor-top))))

(a/defn hint! :void [[text [:slice-const :u8]]]
  (k/= hint-length (k/min 255 (:len text)))
  (k/memcpy (a/slice hint-text 0 hint-length) (a/slice text 0 hint-length)))

(a/defn inside? :bool [[x :f32] [y :f32] [w :f32] [h :f32]]
  (and (k/>= mouse-x x)
       (k/< mouse-x (k/+ x w))
       (k/>= mouse-y y)
       (k/< mouse-y (k/+ y h))))

(a/defn divider-input! :void []
  (when (k/== workspace-mode 1)
    (k/return))
  (when (k/> route-menu 0)
    (k/= divider-drag false)
    (k/return))
  (when (and clicked (inside? 16.0 (k/- editor-top 4.0) (main-width) 8.0))
    (set-state! [trim-drag 0
                 name-focus false
                 clicked false])
    (if double-clicked
      (do
        (set-editor-top! 444.0)
        (k/= divider-drag false))
      (set-state! [divider-grab (k/- (k/as (k/floatCast mouse-y) :f32) editor-top)
                   divider-drag true])))
  (when divider-drag
    (set-editor-top! (k/- (k/as (k/floatCast mouse-y) :f32) divider-grab))
    (k/= clicked false)
    (when (k/! mouse-down)
      (k/= divider-drag false))))

(a/defn playing-preview? :bool []
  (if mix-mode
    (mixer/playing?)
    (and voice-ready
         (k/< preview-node 1024)
         (k/!= (audio/ma_sound_is_playing (k/& (a/get voices voice-slot))) 0))))

(a/defn selected-preview? :bool []
  (and (k/! mix-mode)
       voice-ready
       (k/== preview-node selected)))

(a/defn paused-preview? :bool []
  (and preview-paused
       (or mix-mode voice-ready)))

(a/defn transport-action :u32 []
  ;; Global transport owns the loaded sound, regardless of passage selection.
  (if (or (k/== workspace-mode 0)
          mix-mode
          (playing-preview?)
          (paused-preview?))
    6
    35))

(a/defn pause-preview! :void []
  (when (playing-preview?)
    (if mix-mode
      (mixer/pause!)
      (k/= :_ (audio/ma_sound_stop (k/& (a/get voices voice-slot)))))
    (k/= preview-paused true)))

(a/defn resume-preview! :bool []
  (when mix-mode
    (k/= mixer/output-index headphones)
    (when (k/! (mixer/open!))
      (k/return false))
    (when (k/>= (mixer/cursor-frame) mixer/duration)
      (mixer/seek! 0))
    (mixer/play!)
    (k/= preview-paused false)
    (k/return true))
  (when (or (k/! voice-ready)
            (k/! preview-paused))
    (k/return false))
  (when (k/! (prepare-playback!))
    (k/return false))
  (when (k/!= (audio/ma_sound_start (k/& (a/get voices voice-slot))) audio/MA_SUCCESS)
    (k/return false))
  (k/= preview-paused false)
  true)

(a/defn cursor-seconds :f32 []
  (when mix-mode
    (k/return (k// (k/as (k/floatFromInt (mixer/cursor-frame)) :f32) 48000.0)))
  (when (k/! voice-ready)
    (k/return seek-seconds))
  (let [cursor (k/var (k/u64 0))]
    (k/= :_ (audio/ma_sound_get_cursor_in_pcm_frames
             (k/& (a/get voices voice-slot)) (k/& cursor)))
    (k// (k/as (k/floatFromInt cursor) :f32) 48000.0)))

(a/defn handle-stopped-outputs! :u32
  "Render-thread only. Pause lost outputs without discarding decoded takes or mix PCM." []
  (let [result (k/var (k/u32 0))]
    (when (and playback-ready
               (k/! playback-interrupted))
      (let [device ((:ma_engine_get_device recorder/api)
                    (k/ptrCast (k/& playback-engine)))]
        (when (and (k/!= device k/null)
                   (k/== ((:ma_device_get_state recorder/api) device)
                         (:ma_device_state_stopped recorder/api)))
          (when voice-ready
            (when (and (k/! mix-mode) (k/== preview-node selected))
              (k/= seek-seconds (cursor-seconds)))
            (k/= :_ (audio/ma_sound_stop (k/& (a/get voices voice-slot))))
            (when (k/! mix-mode)
              (k/= preview-paused true)))
          (k/= playback-interrupted true)
          (k/atomicStore :u32 (k/& playback-level) 0 :.release)
          (k/= result (k/| result 1)))))
    (when (mixer/handle-stopped-output!)
      (when mix-mode
        (k/= preview-paused true))
      (k/= result (k/| result 2)))
    result))

(a/defn seek-preview! :void []
  (when voice-ready
    (k/= :_ (audio/ma_sound_seek_to_pcm_frame
             (k/& (a/get voices voice-slot))
             (k/intFromFloat (k/* 48000.0 (k/max 0.0 (k/min take-seconds seek-seconds))))))))

(a/defn position! :void [[seconds :f32]]
  (when mix-mode
    (mixer/seek! (k/intFromFloat (k/* 48000.0 (k/max 0.0 seconds))))
    (k/return))
  (k/= seek-seconds (k/max 0.0 (k/min take-seconds seconds)))
  (when (and voice-ready (k/== preview-node selected))
    (seek-preview!)))

(a/defn pan! :void [[seconds :f32]]
  (set-state! [timeline-start (k/max 0.0 (k/min (k/- 60.0 timeline-seconds) (k/+ timeline-start seconds)))
               follow-playhead false]))

(a/defn track-offset-at :u32 [[y :f32] [count :u32]]
  (let [rows (k/as (k/floatFromInt (visible-row-count)) :f32)
        total (k/as (k/floatFromInt (k/max (visible-row-count) count)) :f32)
        height (k/max 18.0 (k/* (track-height) (k// rows total)))
        fraction (k/max 0.0 (k/min 1.0 (k// (k/- y 169.0 bar-grab) (k/max 1.0 (k/- (track-height) height)))))]
    (k/intFromFloat (k/+ 0.5 (k/* (k/- total rows) fraction)))))

(a/defn time-at :f32 [[x :f32]]
  (k/+ timeline-start (k/* (k/max 0.0 (k/min 1.0 (k// (k/- x 242.0) (timeline-width)))) timeline-seconds)))

(a/defn zoom! :void [[factor :f32]]
  (set-state! [timeline-seconds (k/max 2.0 (k/min 60.0 (k/* timeline-seconds factor)))
               timeline-start (k/min timeline-start (k/- 60.0 timeline-seconds))]))

(a/defn zoom-at! :void [[factor :f32] [x :f32]]
  (let [anchor (time-at x)
        ratio (k/max 0.0 (k/min 1.0 (k// (k/- x 242.0) (timeline-width))))]
    (zoom! factor)
    (k/= timeline-start (k/max 0.0 (k/min (k/- 60.0 timeline-seconds) (k/- anchor (k/* ratio timeline-seconds))))))
  (k/= follow-playhead false))

(a/defn scroll-by! :void [[dx :f32] [dy :f32] [zoom? :bool] [horizontal? :bool]]
  (when (k/> route-menu 0)
    (k/return))
  (when (and (k/== workspace-mode 2)
             (inside? 16.0 143.0 (main-width) (track-height)))
    (when (k/!= (k/as (k/intFromFloat track-scroll) :u32) track-offset)
      (k/= track-scroll (k/floatFromInt track-offset)))
    (set-state! [track-scroll (k/max 0.0 (k/min
                                          (k/as (k/floatFromInt
                                                 (k/- (k/max (visible-row-count) scene/passage-entity-count)
                                                      (visible-row-count))) :f32)
                                          (k/- track-scroll dy)))
                 track-offset (k/intFromFloat track-scroll)])
    (k/return))
  (when (k/== workspace-mode 1)
    (cond
      (inside? 242.0 204.0 (timeline-width) (record-pane-height))
      (k/= record-scroll (k/max 0.0 (k/min (k/max 0.0 (k/- record-text-height (record-pane-height)))
                                           (k/- record-scroll (k/* dy 24.0)))))
      (inside? 16.0 150.0 220.0 (k/- window-height 220.0))
      (do
        (when (k/!= (k/as (k/intFromFloat track-scroll) :u32) track-offset)
          (k/= track-scroll (k/floatFromInt track-offset)))
        (set-state! [track-scroll (k/max 0.0 (k/min
                                              (k/as (k/floatFromInt (k/- (k/max (record-row-count) scene/passage-entity-count) (record-row-count))) :f32)
                                              (k/- track-scroll dy)))
                     track-offset (k/intFromFloat track-scroll)])))
    (k/return))
  (cond
    (inside? 242.0 (editor-y 489.0) (timeline-width) (k/+ (editor-text-height) 9.0))
    (k/= focus-scroll (k/max 0.0 (k/min (k/max 0.0 (k/- focus-height (editor-text-height))) (k/- focus-scroll (k/* dy 20.0)))))
    (inside? 16.0 143.0 (main-width) (editor-y 299.0))
    (cond
      (and (k/== page 0) zoom?)
      (zoom-at! (k/exp (k/* -0.12 dy)) (k/floatCast mouse-x))
      (and (k/== page 0) horizontal?)
      (pan! (k/* -0.04 dy timeline-seconds))
      :else
      (do
        (when (k/!= dx 0.0)
          (pan! (k/* -0.04 dx timeline-seconds)))
        (if (k/== page 1)
          (k/= scroll (k/max 0.0 (k/min (k/max 0.0 (k/- content-height (editor-y 256.0))) (k/- scroll (k/* dy 22.0)))))
          (do
            (when (k/!= (k/as (k/intFromFloat track-scroll) :u32) track-offset)
              (k/= track-scroll (k/floatFromInt track-offset)))
            (set-state! [track-scroll (k/max 0.0 (k/min (k/as (k/floatFromInt (k/- (k/max (visible-row-count) scene/passage-entity-count) (visible-row-count))) :f32) (k/- track-scroll dy)))
                         track-offset (k/intFromFloat track-scroll)])))))))

(a/defn pinch-at! :bool [[amount :f64] [x :f64] [y :f64]]
  ;; Pinch belongs to Edit's timeline only, never script, routing or recording.
  ;; Positive magnification opens the fingers: show less time, anchored at x.
  (when (or (k/!= workspace-mode 0)
            (k/!= page 0)
            (k/> route-menu 0)
            (k/! (and (k/>= x 242.0) (k/< x (k/+ 242.0 (timeline-width)))
                      (k/>= y 143.0) (k/< y (editor-y 442.0))))
            (k/! (and (k/> amount -100.0) (k/< amount 100.0)))
            (k/== amount 0.0))
    (k/return false))
  ;; A single gesture cannot usefully exceed the full 2–60 second zoom range.
  ;; Bound before exp/floatCast so finite queued bursts cannot overflow f32.
  (zoom-at! (k/floatCast (k/exp (k/max -4.0 (k/min 4.0 (k/- amount)))))
            (k/floatCast x))
  true)

(a/defn move-passage! :void [[down? :bool] [page? :bool]]
  ;; Selection is silent and never changes the loaded sound's transport owner.
  (when (or (busy?)
            (k/> capture-phase 0)
            (k/!= (k/atomicLoad :u32 (k/& pending) :.acquire) 0)
            name-focus
            (k/> route-menu 0)
            (k/== scene/passage-entity-count 0)
            (and (k/== workspace-mode 0) (k/!= page 0)))
    (k/return))
  (let [rows (if (k/== workspace-mode 1)
               (record-row-count)
               (visible-row-count))
        last (k/- scene/passage-entity-count 1)
        current (k/min selected last)
        distance (if page? rows 1)
        next (if down?
               (k/+ current (k/min distance (k/- last current)))
               (k/- current (k/min distance current)))
        offset (cond
                 (k/< next track-offset) next
                 (k/>= next (k/+ track-offset rows)) (k/- (k/+ next 1) rows)
                 :else track-offset)]
    (when (k/!= next selected)
      (set-state! [selected next
                   seek-seconds 0.0
                   focus-scroll 0.0
                   record-scroll 0.0
                   name-drag false
                   trim-drag 0]))
    (set-state! [track-offset (k/min offset (k/- (k/max rows scene/passage-entity-count) rows))
                 track-scroll (k/floatFromInt track-offset)])))

(a/defn install-gestures! :bool []
  (when (k/== studio-window k/null) (k/return false))
  ((:lp_studio_gestures_attach gestures-api)
   ((:glfwGetCocoaWindow gestures-api) (k/ptrCast studio-window))))

(a/defn- poll-gestures! :void []
  (let [event (k/var (mem/zeroes (:lp_studio_pinch gestures-api)))]
    (k/while ((:lp_studio_gestures_poll gestures-api) (k/& event))
      (when (and (k/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
                 (k/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0))
        (k/= :_ (pinch-at! (:magnification event)
                           (:x event) (:y event)))))))

(a/defn scrolled! :void {:zig/qualifiers "callconv(.c)"}
  [[window [:optional [:* glfw/GLFWwindow]]] [dx :f64] [dy :f64]]
  (when (k/== window k/null)
    (k/return))
  (if attached
    (do
      (update-layout!)
      (glfw/glfwGetCursorPos window (k/& mouse-x) (k/& mouse-y))
      (scroll-by! (k/floatCast dx) (k/floatCast dy)
                  (or (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_ALT) glfw/GLFW_PRESS)
                      (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_ALT) glfw/GLFW_PRESS))
                  (or (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_SHIFT) glfw/GLFW_PRESS)
                      (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_SHIFT) glfw/GLFW_PRESS))))
    (when (k/!= previous-scroll k/null)
      ((a/unwrap previous-scroll) window dx dy))))

(a/defn mouse-event! :void {:zig/qualifiers "callconv(.c)"}
  [[window [:optional [:* glfw/GLFWwindow]]] [button :c_int] [action :c_int] [mods :c_int]]
  (when (and attached (k/== button glfw/GLFW_MOUSE_BUTTON_LEFT))
    (k/= mouse-down (k/== action glfw/GLFW_PRESS))
    (when mouse-down
      (glfw/glfwGetCursorPos window (k/& press-x) (k/& press-y))
      (let [now (glfw/glfwGetTime)]
        (set-state! [event-double (and (k/< (k/- now last-press-time) 0.32)
                                       (k/< (k/abs (k/- press-x last-press-x)) 5.0)
                                       (k/< (k/abs (k/- press-y last-press-y)) 5.0))
                     last-press-time now
                     last-press-x press-x
                     last-press-y press-y]))
      (k/= event-pressed true)))
  (when (and (k/! attached) (k/!= previous-mouse k/null))
    ((a/unwrap previous-mouse) window button action mods)))

(a/defn composing-name? :bool []
  (and name-focus
       (k/!= studio-window k/null)
       ((:lp_studio_ime_active gestures-api)
        ((:glfwGetCocoaWindow gestures-api) (k/ptrCast studio-window)))))

(a/defn name-copy! :void [[cut? :bool]]
  (when (or (k/== studio-window k/null) (k/== name-anchor name-caret))
    (k/return))
  (let [a (k/min name-anchor name-caret)
        b (k/max name-anchor name-caret)
        text (k/var (k/as (mem/zeroes [:array 128 :u8]) [:array 128 :u8]))]
    (k/memcpy (a/slice text 0 (k/- b a)) (a/slice edit-name a b))
    (glfw/glfwSetClipboardString studio-window (k/& text)))
  (when cut?
    (name-checkpoint!)
    (name-delete!)
    (name-settle-caret!)))

(a/defn typed! :void {:zig/qualifiers "callconv(.c)"}
  [[window [:optional [:* glfw/GLFWwindow]]] [cp :u32]]
  (when (and (k/! attached) (k/!= previous-char k/null))
    ((a/unwrap previous-char) window cp))
  (when (and attached
             name-focus
             (k/! (busy?))
             (k/>= cp 32)
             (k/!= cp 127)
             (k/<= cp 1114111)
             (or (k/< cp 55296) (k/> cp 57343)))
    (let [n (k/usize (if (k/< cp 128)
                       1
                       (if (k/< cp 2048)
                         2
                         (if (k/< cp 65536)
                           3
                           4))))
          removed (k/- (k/max name-caret name-anchor) (k/min name-caret name-anchor))]
      (when (k/> (k/+ (k/- name-length removed) n) 120)
        (k/return))
      (name-checkpoint!)
      (name-delete!)
      (dotimes [i (k/- name-length name-caret)]
        (let [source (k/- name-length i 1)]
          (k/= (a/get edit-name (k/+ source n)) (a/get edit-name source))))
      (k/= (a/get edit-name name-caret)
           (k/intCast (if (k/== n 1)
                        cp
                        (if (k/== n 2)
                          (k/+ 192 (k// cp 64))
                          (if (k/== n 3)
                            (k/+ 224 (k// cp 4096))
                            (k/+ 240 (k// cp 262144)))))))
      (dotimes [i (k/- n 1)]
        (let [divisor (k/u32 (if (k/== (k/- n i) 4)
                               4096
                               (if (k/== (k/- n i) 3)
                                 64
                                 1)))]
          (k/= (a/get edit-name (k/+ name-caret i 1)) (k/intCast (k/+ 128 (k/mod (k// cp divisor) 64))))))
      (set-state! [name-length (k/+ name-length n)
                   name-caret (k/+ name-caret n)
                   name-anchor name-caret])
      (when (k/! name-batch)
        (name-settle-caret!)))))

(a/defn name-paste! :void [[text [:slice-const :u8]]]
  (when (k/! (unicode/utf8ValidateSlice text))
    (k/return))
  (set-state! [name-batch true
               name-batch-recorded false])
  (k/defer (do
             (when name-batch-recorded
               (name-settle-caret!))
             (set-state! [name-batch false
                          name-batch-recorded false])))
  (let [i (k/var (k/usize 0))]
    (k/while (k/< i (:len text))
      (let [end (text-boundary text i false)
            scan (k/var (k/usize i))
            bytes (k/var (k/usize 0))
            removed (k/- (k/max name-caret name-anchor) (k/min name-caret name-anchor))]
        ;; Reserve the entire character before changing text or selection.
        ;; Ignored controls consume no capacity; CR/LF/TAB become spaces.
        (k/while (k/< scan end)
          (let [n (catch (unicode/utf8ByteSequenceLength (a/get text scan)) (k/return))
                cp (catch (unicode/utf8Decode (a/slice text scan (k/+ scan n))) (k/return))]
            (when (or (and (k/>= cp 32) (k/!= cp 127))
                      (k/== cp 9)
                      (k/== cp 10)
                      (k/== cp 13))
              (k/= bytes (k/+ bytes n)))
            (k/= scan (k/+ scan n))))
        (when (k/> (k/+ (k/- name-length removed) bytes) 120)
          (k/break))
        (k/while (k/< i end)
          (let [n (catch (unicode/utf8ByteSequenceLength (a/get text i)) (k/return))
                cp (catch (unicode/utf8Decode (a/slice text i (k/+ i n))) (k/return))]
            (typed! studio-window (if (or (k/== cp 10)
                                          (k/== cp 13)
                                          (k/== cp 9))
                                    32
                                    cp))
            (k/= i (k/+ i n))))))))

(a/defn request! :void [[action :u32]]
  (when (k/== (k/atomicLoad :u8 (k/& busy) :.acquire) 0)
    (k/atomicStore :u8 (k/& busy) 1 :.release)
    (k/atomicStore :u32 (k/& pending) action :.release)))

(a/defn key-event! :void {:zig/qualifiers "callconv(.c)"}
  [[window [:optional [:* glfw/GLFWwindow]]] [key :c_int] [scancode :c_int] [action :c_int] [mods :c_int]]
  ;; Enter confirms composition, not Rename; Escape cancels it, not text focus.
  ;; GLFW still delivers the eventual committed characters through typed!.
  (when (and attached (composing-name?))
    (k/return))
  (when (k/> route-menu 0)
    (when (k/!= action glfw/GLFW_RELEASE)
      (cond
        (k/== key glfw/GLFW_KEY_ESCAPE)
        (k/= route-menu 0)
        (k/== key glfw/GLFW_KEY_DOWN)
        (route-move! true)
        (k/== key glfw/GLFW_KEY_UP)
        (route-move! false)
        (k/== key glfw/GLFW_KEY_HOME)
        (do
          (set-state! [route-focus 0
                       route-offset 0]))
        (k/== key glfw/GLFW_KEY_END)
        (do
          (set-state! [route-focus (k/- (k/max 1 (route-total)) 1)
                       route-offset (k/* (k// route-focus 8) 8)]))
        (k/== key glfw/GLFW_KEY_ENTER)
        (route-select! route-focus)))
    (k/return))
  (when (and name-focus (busy?))
    (when (k/== key glfw/GLFW_KEY_ESCAPE)
      (k/= name-focus false))
    (k/return))
  (when (and attached (k/!= action glfw/GLFW_RELEASE))
    (if name-focus
      (let [extend? (k/!= (k/& mods glfw/GLFW_MOD_SHIFT) 0)
            command? (k/!= (k/& mods (k/| glfw/GLFW_MOD_SUPER glfw/GLFW_MOD_CONTROL)) 0)]
        (cond
          (and command? (k/== key glfw/GLFW_KEY_Z))
          (name-undo! extend?)
          (and command? (k/== key glfw/GLFW_KEY_Y))
          (name-undo! true)
          (k/== key glfw/GLFW_KEY_ESCAPE)
          (k/= name-focus false)
          (k/== key glfw/GLFW_KEY_ENTER)
          (do
            (k/= name-focus false)
            (request! 9))
          (and command? (k/== key glfw/GLFW_KEY_A))
          (do
            (set-state! [name-anchor 0
                         name-caret name-length]))
          (and command? (k/== key glfw/GLFW_KEY_C))
          (name-copy! false)
          (and command? (k/== key glfw/GLFW_KEY_X))
          (name-copy! true)
          (and command? (k/== key glfw/GLFW_KEY_V))
          (when (k/!= studio-window k/null)
            (let [text (glfw/glfwGetClipboardString studio-window)]
              (when (k/!= text k/null)
                (name-paste! (mem/span text)))))
          (k/== key glfw/GLFW_KEY_HOME)
          (name-move! 0 extend?)
          (k/== key glfw/GLFW_KEY_END)
          (name-move! name-length extend?)
          (k/== key glfw/GLFW_KEY_LEFT)
          (name-move! (if command?
                        0
                        (if (and (k/! extend?) (k/!= name-caret name-anchor))
                          (k/min name-caret name-anchor)
                          (name-previous name-caret))) extend?)
          (k/== key glfw/GLFW_KEY_RIGHT)
          (name-move! (if command?
                        name-length
                        (if (and (k/! extend?) (k/!= name-caret name-anchor))
                          (k/max name-caret name-anchor)
                          (name-next name-caret))) extend?)
          (k/== key glfw/GLFW_KEY_BACKSPACE)
          (when (or (k/> name-caret 0) (k/!= name-caret name-anchor))
            (name-checkpoint!)
            (when (k/== name-caret name-anchor)
              (k/= name-anchor (name-previous name-caret)))
            (name-delete!)
            (name-settle-caret!))
          (k/== key glfw/GLFW_KEY_DELETE)
          (when (or (k/< name-caret name-length) (k/!= name-caret name-anchor))
            (name-checkpoint!)
            (when (k/== name-caret name-anchor)
              (k/= name-anchor (name-next name-caret)))
            (name-delete!)
            (name-settle-caret!))))
      (if (and (k/== mods 0)
               (or (k/== key glfw/GLFW_KEY_UP)
                   (k/== key glfw/GLFW_KEY_DOWN)
                   (k/== key glfw/GLFW_KEY_PAGE_UP)
                   (k/== key glfw/GLFW_KEY_PAGE_DOWN)))
        (move-passage! (or (k/== key glfw/GLFW_KEY_DOWN)
                           (k/== key glfw/GLFW_KEY_PAGE_DOWN))
                       (or (k/== key glfw/GLFW_KEY_PAGE_UP)
                           (k/== key glfw/GLFW_KEY_PAGE_DOWN)))
        (when (k/== action glfw/GLFW_PRESS)
          (cond
            (and (k/== key glfw/GLFW_KEY_Z)
                 (k/!= (k/& mods (k/| glfw/GLFW_MOD_SUPER glfw/GLFW_MOD_CONTROL)) 0))
            (request! (if (k/!= (k/& mods glfw/GLFW_MOD_SHIFT) 0)
                        28
                        27))
            (k/== key glfw/GLFW_KEY_SPACE)
            (if (busy?)
              (k/atomicStore :u32 (k/& pending) 2 :.release)
              (request! (transport-action)))
            (k/== key glfw/GLFW_KEY_F1)
            (glfw/glfwFocusWindow scene/window)
            (k/== key glfw/GLFW_KEY_F2)
            (select-workspace! (k/mod (k/+ workspace-mode 1) 3))
            (k/== key glfw/GLFW_KEY_ESCAPE)
            (k/atomicStore :u32 (k/& pending) 2 :.release)
            (k/== key glfw/GLFW_KEY_HOME)
            (when (k/! (busy?))
              (position! 0.0))
            (k/== key glfw/GLFW_KEY_EQUAL)
            (zoom-at! 0.8 (timeline-center))
            (k/== key glfw/GLFW_KEY_MINUS)
            (zoom-at! 1.25 (timeline-center)))))))
  (when (and (k/! attached) (k/!= previous-key k/null))
    ((a/unwrap previous-key) window key scancode action mods)))

(a/defn node-revision :u32 [[index :u32]]
  (:revision (a/get (:nodes (a/get scene/stories scene/active-story)) index)))

(a/defn node-speaker :u32 [[index :u32]]
  (:speaker (a/get (:nodes (a/get scene/stories scene/active-story)) index)))

(a/defstruct PassageFreshness {:layout :extern}
              [[id [:array 64 :u8]] [id-length :u32] [revision :u32] [status :u32]])

(a/defvar passage-freshness [:array 1024 PassageFreshness]
  (mem/zeroes [:array 1024 PassageFreshness]))

(a/defn set-passage-freshness! :void
  [[index :u32] [id [:slice-const :u8]] [revision :u32] [status :u32]]
  (when (or (k/>= index scene/passage-entity-count)
            (k/> (:len id) 64)
            (k/!= revision (node-revision index))
            (k/! (mem/eql :u8 id (node-id index))))
    (k/return))
  (let [entry (k/& (a/get passage-freshness index))]
    (k/memcpy (a/slice (:id entry) 0 (:len id)) id)
    (k/= (a/deref entry)
         (PassageFreshness {:id (:id entry)
                            :id-length (k/intCast (:len id))
                            :revision revision
                            :status status}))))

(a/defn passage-freshness-status :u32 [[index :u32]]
  (when (k/>= index scene/passage-entity-count)
    (k/return 0))
  (when (k/== (:len (node-id index)) 0)
    (k/return 6))
  (let [entry (a/get passage-freshness index)]
    (when (or (k/!= (node-revision index) (:revision entry))
              (k/! (mem/eql :u8 (node-id index)
                            (a/slice (:id entry) 0 (:id-length entry)))))
      (k/return 0))
    (:status entry)))

(a/defn passage-freshness-label [:slice-const :u8] [[index :u32]]
  (let [status (passage-freshness-status index)]
    (cond
      (k/== status 1) "Needs recording"
      (k/== status 2) "Changed - review take"
      (k/== status 3) "Recorded"
      (k/== status 4) "Unverified - review take"
      (k/== status 5) "Interrupted - review take"
      (k/== status 6) "Text only"
      :else "Checking recording...")))

(a/defn passage-status-badge [:slice-const :u8] [[status :u32]]
  (cond
    (k/== status 1) "No take"
    (k/== status 2) "Review"
    (k/== status 3) "Recorded"
    (k/== status 4) "Unverified"
    (k/== status 5) "Interrupted"
    (k/== status 6) "Text only"
    :else "Checking"))

(a/defn clip! :void [[slot :u32] [seconds :f32] [count :u32]]
  (when (k/< slot 32)
    (k/= (:seconds (a/get clip-viewport slot)) seconds)
    (k/= (:count (a/get clip-viewport slot)) count)))

(a/defn clip-start! :void [[slot :u32] [seconds :f32]]
  (when (k/< slot 32)
    (k/= (:start (a/get clip-viewport slot)) seconds)))

(a/defn clip-bin! :void [[slot :u32] [bin :u32] [value :f32]]
  (when (and (k/< slot 32) (k/< bin 128))
    (k/= (a/get (:wave (a/get clip-viewport slot)) bin) value)))

(a/defn trim-at! :void [[x :f32]]
  (let [percent (k/u32 (k/intFromFloat (k/max 0.0 (k/min 100.0 (k/* 100.0 (k// (k/- x 242.0) (timeline-width)))))))]
    (when (k/== trim-drag 1)
      (k/= trim-in (k/min (k/- trim-out 1) percent)))
    (when (k/== trim-drag 2)
      (k/= trim-out (k/max (k/+ trim-in 1) percent)))))

(a/defn selected-take-cursor :f32 [[audio-position :f32]]
  ;; A different passage (or the mix) may still be playing. Its clock does not
  ;; belong on this selected take's waveform. Reuse the one per-frame sample.

  (if (and (k/! mix-mode)
           voice-ready
           (k/== preview-node selected))
    audio-position
    seek-seconds))

(a/defn pixel-align-x :f32 [[raw :f32]]
  (let [scale (k/max 1.0 framebuffer-scale)]
    (k// (k/floor (k/+ 0.5 (k/* raw scale))) scale)))

(a/defn playhead-x :f32 [[seconds :f32]]
  (pixel-align-x (k/+ 242.0 (k/* (timeline-width) (k// (k/- seconds timeline-start) timeline-seconds)))))

(a/defn take-playhead-x :f32 [[seconds :f32] [duration :f32]]
  (let [span (k/- (timeline-width) 2.0)
        scale (k/max 1.0 framebuffer-scale)
        right (k// (k/floor (k/* (k/+ 242.0 span) scale)) scale)]
    (k/min right (pixel-align-x (k/+ 242.0 (k/* span
                                                (k/max 0.0 (k/min 1.0 (k// seconds (k/max 0.00001 duration))))))))))

(a/defn ui-text! :void
  [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32] [rgb :u32]]
  (let [s (k/* scale 1.5)
        index (k/var (k/usize 0))
        cursor (k/var (k/f32 x))]
    (k/while (k/< index (:len text))
      (let [code (scene/glyph-code! text (k/& index))]
        (scene/quad! (k/- cursor (k/* 3.0 s)) (k/+ y (k/- s scale)) (k/* 62.0 s) (k/* 48.0 s)
                     (k/+ 1025.0 (k/as (k/floatFromInt (k/* (k/mod code 16) 64)) :f32))
                     (k/+ 737.0 (k/as (k/floatFromInt (k/* (k/divTrunc code 16) 50)) :f32))
                     62.0 48.0 rgb 1.0 0.0)
        (k/= cursor (k/+ cursor (k/* (a/get scene/ui-glyph-advances code) s)))))))

(a/defn ui-text-width :f32 [[text [:slice-const :u8]] [scale :f32]]
  (let [index (k/var (k/usize 0))
        width (k/var (k/f32 0.0))]
    (k/while (k/< index (:len text))
      (let [code (scene/glyph-code! text (k/& index))]
        (k/= width (k/+ width (k/* (a/get scene/ui-glyph-advances code) scale 1.5)))))
    width))

(a/defn name-display-text! [:slice-const :u8]
  "Compose a bounded draft for the Latin font atlas without changing saved bytes.
  The returned slice borrows the caller's scratch buffer; no heap allocation."
  [[text [:slice-const :u8]] [scratch [:* [:array 513 :i32]]]]
  (let [options (k/| (:UTF8PROC_COMPOSE text-api)
                     (:UTF8PROC_STABLE text-api))
        count ((:utf8proc_decompose text-api)
               (:ptr text) (k/intCast (:len text))
               (k/ptrCast scratch) 512 options)]
    (when (or (k/< count 0) (k/> count 512))
      (k/return "Invalid name"))
    (let [length ((:utf8proc_reencode text-api) (k/ptrCast scratch) count options)]
      (when (k/< length 0)
        (k/return "Invalid name"))
      (let [bytes (k/as (k/ptrCast scratch) [:c-pointer :u8])]
        (a/slice bytes 0 (k/as (k/intCast length) :usize))))))

(a/defn name-text-width :f32 [[text [:slice-const :u8]]]
  (let [scratch (k/var (k/as k/undefined [:array 513 :i32]))]
    (ui-text-width (name-display-text! text (k/& scratch)) 0.24)))

(a/defn number! :void [[value :u32] [x :f32] [y :f32] [scale :f32]]
  (let [digits (k/var (k/as k/undefined [:array 10 :u8]))
        start (k/var (k/usize 10))
        n (k/var (k/u32 value))]
    (k/while true
      (k/= start (k/- start 1))
      (k/= (a/get digits start) (k/intCast (k/+ 48 (k/mod n 10))))
      (k/= n (k// n 10))
      (when (k/== n 0)
        (k/break)))
    (ui-text! (a/slice digits start 10) x y scale 0x26364a)))

(a/defn label! :void [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [color :u32]]
  (let [end (k/var (k/usize (:len text)))]
    (k/while (and (k/> end 0)
                  (k/> (ui-text-width (a/slice text 0 end) 0.24) width))
      (k/= end (k/- end 1))
      (k/while (and (k/> end 0)
                    (k/>= (a/get text end) 128)
                    (k/< (a/get text end) 192))
        (k/= end (k/- end 1))))
    (ui-text! (a/slice text 0 end) x y 0.24 color)))

(a/defn seconds! :void [[seconds :f32] [x :f32] [y :f32] [scale :f32]]
  (let [tenths (k/u32 (k/intFromFloat (k/+ 0.5 (k/* 10.0 (k/max 0.0 seconds)))))
        digits (k/var (k/as k/undefined [:array 12 :u8]))
        start (k/var (k/usize 10))
        n (k/var (k/u32 (k// tenths 10)))]
    (k/= (a/get digits 10) 46)
    (k/= (a/get digits 11) (k/intCast (k/+ 48 (k/mod tenths 10))))
    (k/while true
      (k/= start (k/- start 1))
      (k/= (a/get digits start) (k/intCast (k/+ 48 (k/mod n 10))))
      (k/= n (k// n 10))
      (when (k/== n 0)
        (k/break)))
    (ui-text! (a/slice digits start 12) x y scale 0x26364a)))

(a/defn details! :void [[text [:slice-const :u8]]]
  (k/= details-length (k/min 511 (:len text)))
  (k/memcpy (a/slice details 0 details-length) (a/slice text 0 details-length)))

(a/defn name! :void [[text [:slice-const :u8]]]
  (when (k/! (unicode/utf8ValidateSlice text))
    (k/return))
  (set-state! [name-history-position 0
               name-history-end 0
               name-length (text-prefix text 120)])
  (k/memcpy (a/slice edit-name 0 name-length) (a/slice text 0 name-length))
  (set-state! [name-caret name-length
               name-anchor name-length
               name-view 0]))

(a/defn entered-name [:slice-const :u8] []
  (a/slice edit-name 0 name-length))

(a/defn- reset-take-name! :void [[text [:slice-const :u8]]]
  ;; An edit belongs to its previous take, never the newly selected target.
  (cancel-name-composition!)
  (k/= name-focus false)
  (name! text))

(a/defn set-wave! :void [[index :u32] [value :f32]]
  (when (k/< index 128)
    (k/= (a/get wave index) value)))

(a/defn waveform-owner! :void [[id [:slice-const :u8]]]
  (when (k/> (:len id) 64) (k/return))
  (k/memcpy (a/slice waveform-owner 0 (:len id)) id)
  (set-state! [waveform-owner-length (:len id)
               waveform-uploaded true]))

(a/defn name-hit :usize [[x :f64]]
  (let [p (k/var (k/usize name-view))]
    (k/while (k/< p name-length)
      (let [next (name-next p)
            a (name-text-width (a/slice edit-name name-view p))
            b (name-text-width (a/slice edit-name name-view next))]
        (when (k/< (k/- x 36.0) (k// (k/+ a b) 2.0))
          (k/break))
        (k/= p next)))
    p))

(a/defn name-drag-to! :void
  "Extend a draft selection, with time-based edge scrolling independent of FPS." [[x :f64] [elapsed :f64]]
  (let [left? (k/< x 36.0)
        right? (k/> x 208.0)
        overshoot (if left? (k/- 36.0 x) (k/max 0.0 (k/- x 208.0)))
        interval (k/max 0.015 (k// 0.08 (k/+ 1.0 (k// overshoot 24.0))))]
    (if (or left? right?)
      (do
        (k/= name-drag-elapsed (k/+ name-drag-elapsed (k/min 0.05 (k/max 0.0 elapsed))))
        (k/while (k/>= (k/+ name-drag-elapsed 0.000000001) interval)
          (k/= name-drag-elapsed (k/max 0.0 (k/- name-drag-elapsed interval)))
          (if left?
            (k/= name-view (name-previous name-view))
            (when (k/> (name-text-width (a/slice edit-name name-view name-length)) 172.0)
              (k/= name-view (name-next name-view))))))
      (k/= name-drag-elapsed 0.0))
    ;; Hit-test only the visible field, not all offscreen text at the raw pointer.
    (name-move! (name-hit (k/max 36.0 (k/min 208.0 x))) true)))

(a/defn name-field! :void []
  (scene/rect! 28.0 (bottom-y 621.0) 192.0 28.0 (if name-focus
                                                  0xffffff
                                                  0xeef2f7) 0.0)
  (when (and clicked
             (k/! (busy?))
             (inside? 28.0 (bottom-y 621.0) 192.0 28.0))
    (cancel-name-composition!)
    (k/= name-focus true)
    (name-move! (name-hit mouse-x) false)
    (set-state! [name-drag (k/! double-clicked)
                 name-drag-elapsed 0.0
                 name-pointer-time (glfw/glfwGetTime)])
    (when double-clicked
      (set-state! [name-anchor 0
                   name-caret name-length])))
  (let [now (glfw/glfwGetTime)]
    (when (and name-drag
               mouse-down
               (k/! (busy?)))
      (name-drag-to! mouse-x (k/- now name-pointer-time)))
    (k/= name-pointer-time now))
  (when (k/! mouse-down)
    (set-state! [name-drag false
                 name-drag-elapsed 0.0]))
  (when name-focus
    (k/= name-view (k/min name-view name-caret))
    (k/while (k/> (name-text-width (a/slice edit-name name-view name-caret)) 172.0)
      (k/= name-view (name-next name-view))))
  (let [end (k/var (k/usize name-view))]
    (k/while (k/< end name-length)
      (let [next (name-next end)]
        (when (k/> (name-text-width (a/slice edit-name name-view next)) 176.0)
          (k/break))
        (k/= end next)))
    (when (and name-focus (k/!= name-caret name-anchor))
      (let [a (k/max name-view (k/min end (k/min name-caret name-anchor)))
            b (k/max a (k/min end (k/max name-caret name-anchor)))
            x (name-text-width (a/slice edit-name name-view a))]
        (scene/rect! (k/+ 36.0 x) (bottom-y 623.0) (name-text-width (a/slice edit-name a b)) 24.0 0xdce8ff 0.0)))
    (if (and (k/== name-length 0) (k/! name-focus))
      (ui-text! "Take / profile name" 36.0 (bottom-y 625.0) 0.24 0x42566b)
      (let [scratch (k/var (k/as k/undefined [:array 513 :i32]))]
        (ui-text! (name-display-text! (a/slice edit-name name-view end) (k/& scratch))
                  36.0 (bottom-y 625.0) 0.24 0x182637))))
  (when name-focus
    (scene/rect! (k/+ 36.0 (name-text-width (a/slice edit-name name-view name-caret)))
                 (bottom-y 625.0) 1.0 20.0 0x356cd5 0.0))
  (when (composing-name?)
    (scene/rect! 28.0 (bottom-y 648.0) 192.0 2.0 0xb37726 0.0)
    (hint! "Input method composing: Enter confirms text; Escape cancels composition. Name is not saved yet."))
  (when (inside? 28.0 (bottom-y 621.0) 192.0 28.0)
    (glfw/glfwSetCursor studio-window text-cursor)
    (when (k/! (composing-name?))
      (hint! "Name: Cmd/Ctrl+Z undo, Shift+Z redo; A/C/X/V; Enter saves"))))

(a/defn sync-name-input! :void []
  ;; AppKit presents marked text with system shaping/fonts; committed text still
  ;; enters typed!. It never activates the application or the other game window.
  (when (k/== studio-window k/null)
    (k/return))
  (if (and name-focus
           (k/! (busy?))
           (k/!= workspace-mode 1))
    (k/= :_ ((:lp_studio_ime_focus gestures-api)
             ((:glfwGetCocoaWindow gestures-api) (k/ptrCast studio-window))
             (k/+ 36.0 (name-text-width (a/slice edit-name name-view name-caret)))
             (bottom-y 625.0)
             24.0))
    (cancel-name-composition!)))

(a/defn waveform! :void [[y :f32]]
  (scene/rect! 242.0 y (timeline-width) 65.0 0xf3f6fa 0.0)
  (when (k/! (selected-waveform?))
    (ui-text! "Loading selected take..." 254.0 (k/+ y 24.0) 0.23 0x687787)
    (k/return))
  (when (and (k/== capture-phase 0)
             (k/<= take-seconds 0.0))
    (ui-text! "No take yet. Record this passage to hear and edit it."
              254.0 (k/+ y 24.0) 0.23 0x687787)
    (k/return))
  (let [maximum (k/var (k/f32 0.00001))]
    (dotimes [i 128]
      (k/= maximum (k/max maximum (a/get wave i))))
    (dotimes [i 128]
      (let [height (k/* 55.0 (k// (a/get wave i) maximum))
            x (k/+ 244.0 (k/* (k// (k/- (timeline-width) 2.0) 128.0) (k/as (k/floatFromInt i) :f32)))]
        (scene/rect! x (k/+ y (k// (k/- 65.0 height) 2.0)) 2.0 (k/max 1.0 height)
                     (if (and (k/== capture-phase 2)
                              (mem/eql :u8 (waveform-passage-id) (captured-id)))
                       0xc63535
                       (if (or (k/< (k/* i 100) (k/* trim-in 128)) (k/> (k/* i 100) (k/* trim-out 128)))
                         0xd9e7ff
                         0x356cd5)) 0.0))))
  (when (and (k/!= workspace-mode 1) (k/== capture-phase 0))
    (scene/rect! (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-in) :f32))) y 3.0 65.0 0x356cd5 0.0)
    (scene/rect! (k/min (content-x 881.0) (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-out) :f32)))) y 3.0 65.0 0x356cd5 0.0))
  ;; The take editor needs the same cursor as Record; trim handles aren't a
  ;; playback position. Both panes use the single audio sample for this frame.
  (when (and (k/== capture-phase 0)
             (k/> take-seconds 0.0))
    (scene/rect! (take-playhead-x (selected-take-cursor frame-cursor) take-seconds) y 2.0 65.0 0x356cd5 0.0)))

(a/defn click-at! :void
  "Development QA input, consumed by the exact same hit-testing as physical clicks." [[x :f64] [y :f64]]
  (set-state! [test-x x
               test-y y
               test-click true]))

(a/defn status! :void [[text [:slice-const :u8]]]
  (k/= status-length (k/min 255 (:len text)))
  (k/memcpy (a/slice status-text 0 status-length) (a/slice text 0 status-length)))

(a/defn alert! :void [[text [:slice-const :u8]]]
  (k/= alert-length (k/min 255 (:len text)))
  (k/memcpy (a/slice alert-text 0 alert-length) (a/slice text 0 alert-length)))

(a/defn current-alert [:slice-const :u8] []
  (a/slice alert-text 0 alert-length))

(a/defn meter-labels! :void [[input [:slice-const :u8]] [returned [:slice-const :u8]]]
  (set-state! [input-meter-length (k/min 63 (:len input))
               return-meter-length (k/min 63 (:len returned))])
  (k/memcpy (a/slice input-meter-text 0 input-meter-length) (a/slice input 0 input-meter-length))
  (k/memcpy (a/slice return-meter-text 0 return-meter-length) (a/slice returned 0 return-meter-length)))

(a/defn begin-capture-presentation! :void []
  ;; Publish one coherent frame immediately, before the worker's next meter /
  ;; waveform refresh. Never recolor the previous saved take as new live PCM.
  (set-state! [busy 1
               capture-phase 2
               take-seconds 0.0])
  (dotimes [i 128]
    (set-wave! (k/intCast i) 0.0))
  (waveform-owner! (captured-id))
  (meter-labels! (if (effects-pass?)
                   "Take send: waiting for signal"
                   "Input: waiting for signal")
                 (if (capture-fx?)
                   "FX: waiting for signal"
                   "FX: bypassed (Dry mode)"))
  (alert! ""))

(a/defn icon-triangle! :void
  [[x1 :f32] [y1 :f32] [x2 :f32] [y2 :f32] [x3 :f32] [y3 :f32] [rgb :u32]]
  (scene/vertex! x1 y1 0.0 0.0 rgb 0.0 0.0)
  (scene/vertex! x2 y2 0.0 0.0 rgb 0.0 0.0)
  (scene/vertex! x3 y3 0.0 0.0 rgb 0.0 0.0))

(a/defn- rounded-rect! :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [radius :f32] [color :u32]]
  (let [r (k/min radius (k// (k/min w h) 2.0))]
    (scene/rect! (k/+ x r) y (k/- w (k/* 2.0 r)) h color 0.0)
    (scene/rect! x (k/+ y r) r (k/- h (k/* 2.0 r)) color 0.0)
    (scene/rect! (k/- (k/+ x w) r) (k/+ y r) r (k/- h (k/* 2.0 r)) color 0.0)
    (dotimes [corner 4]
      (let [left? (or (k/== corner 0) (k/== corner 3))
            cx (if left?
                 (k/+ x r)
                 (k/- (k/+ x w) r))
            cy (if (k/< corner 2)
                 (k/+ y r)
                 (k/- (k/+ y h) r))
            base (k/+ 3.14159265 (k/* 1.57079633 (k/as (k/floatFromInt corner) :f32)))]
        (dotimes [segment 6]
          (let [a (k/+ base (k/* 0.26179939 (k/as (k/floatFromInt segment) :f32)))
                b (k/+ a 0.26179939)]
            (icon-triangle! cx cy
                            (k/+ cx (k/* r (k/cos a))) (k/+ cy (k/* r (k/sin a)))
                            (k/+ cx (k/* r (k/cos b))) (k/+ cy (k/* r (k/sin b)))
                            color)))))))

(a/defn- control-surface! :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [fill :u32] [border :u32]]
  (rounded-rect! x y w h 5.0 border)
  (rounded-rect! (k/+ x 1.0) (k/+ y 1.0) (k/- w 2.0) (k/- h 2.0) 4.0 fill))

(a/defn button! :bool [[label [:slice-const :u8]] [x :f32] [y :f32] [w :f32]]
  (let [hover (and (k/>= mouse-x x)
                   (k/< mouse-x (k/+ x w))
                   (k/>= mouse-y y)
                   (k/< mouse-y (k/+ y 28.0)))]
    (control-surface! x y w 28.0
                      (if hover
                        0xf0f5ff
                        0xffffff)
                      (if hover
                        0x8aa9db
                        0xdce2ea))
    (label! label (k/+ x 8.0) (k/+ y 4.0) (k/- w 12.0) 0x182637)
    (when hover
      (glfw/glfwSetCursor studio-window hand-cursor)
      (hint! label))
    (and hover clicked)))

(a/defn enabled-button! :bool
  [[label [:slice-const :u8]] [x :f32] [y :f32] [w :f32] [enabled :bool] [reason [:slice-const :u8]]]
  (when enabled
    (k/return (button! label x y w)))
  (rounded-rect! x y w 28.0 5.0 0xf3f5f8)
  (label! label (k/+ x 8.0) (k/+ y 4.0) (k/- w 12.0) 0x78869a)
  (when (inside? x y w 28.0)
    (hint! reason))
  false)

(a/defn icon-circle! :void [[x :f32] [y :f32] [radius :f32] [rgb :u32]]
  (dotimes [i 32]
    (let [a (k/* 0.19634954 (k/as (k/floatFromInt i) :f32))
          b (k/+ a 0.19634954)]
      (icon-triangle! x y (k/+ x (k/* radius (k/cos a))) (k/+ y (k/* radius (k/sin a)))
                      (k/+ x (k/* radius (k/cos b))) (k/+ y (k/* radius (k/sin b))) rgb))))

(a/defn icon-button! :bool [[kind :u32] [label [:slice-const :u8]] [x :f32] [y :f32]
                             [w :f32] [enabled :bool] [active :bool]]
  (let [hover (inside? x y w 30.0)
        color (k/u32 (cond
                       active
                       0xffffff
                       enabled
                       0x182637
                       :else
                       0x78869a))
        background (k/u32 (cond
                            (and active (k/== kind 4))
                            0xc94747
                            active
                            0x356cd5
                            (k/! enabled)
                            0xf3f5f8
                            hover
                            0xf0f5ff
                            :else
                            0xffffff))]
    (control-surface! x y w 30.0 background
                      (if active
                        background
                        0xdce2ea))
    (cond
      (k/== kind 0)
      (icon-triangle! (k/+ x 10.0) (k/+ y 7.0) (k/+ x 24.0) (k/+ y 15.0) (k/+ x 10.0) (k/+ y 23.0) color)
      (k/== kind 1)
      (do
        (scene/rect! (k/+ x 9.0) (k/+ y 7.0) 4.0 16.0 color 0.0)
        (scene/rect! (k/+ x 18.0) (k/+ y 7.0) 4.0 16.0 color 0.0))
      (k/== kind 2)
      (scene/rect! (k/+ x 9.0) (k/+ y 8.0) 13.0 13.0 color 0.0)
      (k/== kind 3)
      (do
        (scene/rect! (k/+ x 7.0) (k/+ y 7.0) 3.0 16.0 color 0.0)
        (icon-triangle! (k/+ x 24.0) (k/+ y 7.0) (k/+ x 24.0) (k/+ y 23.0) (k/+ x 12.0) (k/+ y 15.0) color))
      (k/== kind 4)
      (do
        (icon-circle! (k/+ x 16.0) (k/+ y 15.0) 7.0
                      (cond
                        active
                        0xffffff
                        enabled
                        0xc94747
                        :else
                        0x78869a))
        (when (k/! active)
          (icon-circle! (k/+ x 16.0) (k/+ y 15.0) 5.5 background))))
    (when (k/> w 45.0)
      (label! label (k/+ x 32.0) (k/+ y 5.0) (k/- w 37.0) color))
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

(a/defn paragraph-line-visible? :bool
  [[row :f32] [line-height :f32] [top :f32] [bottom :f32]]
  ;; Inclusive boundaries, with subpixel tolerance for accumulated f32 layout.
  ;; At maximum scroll the last line ends exactly at the pane's bottom.

  (and (k/>= row (k/- top 0.01))
       (k/<= (k/+ row line-height) (k/+ bottom 0.01))))

(a/defn- paragraph-text! :void
  "Clip glyph geometry and atlas coordinates together for pixel-smooth pane scrolling." [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32]
                                                                                         [left :f32] [top :f32] [right :f32] [bottom :f32] [color :u32]]
  (when (k/<= scale 0.0)
    (k/return))
  (let [index (k/var (k/usize 0))
        cursor (k/var (k/f32 x))]
    (k/while (k/< index (:len text))
      (let [code (scene/glyph-code! text (k/& index))
            glyph-x (k/- cursor (k/* 3.0 scale))
            glyph-y (k/+ y scale)
            clipped-x (k/max left glyph-x)
            clipped-y (k/max top glyph-y)
            clipped-right (k/min right (k/+ glyph-x (k/* 62.0 scale)))
            clipped-bottom (k/min bottom (k/+ glyph-y (k/* 78.0 scale)))]
        (when (and (k/> clipped-right clipped-x)
                   (k/> clipped-bottom clipped-y))
          (scene/quad! clipped-x clipped-y
                       (k/- clipped-right clipped-x) (k/- clipped-bottom clipped-y)
                       (k/+ 1.0 (k/* (k/as (k/floatFromInt (k/mod code 32)) :f32) 64.0)
                            (k// (k/- clipped-x glyph-x) scale))
                       (k/+ 1.0 (k/* (k/as (k/floatFromInt (k/divTrunc code 32)) :f32) 80.0)
                            (k// (k/- clipped-y glyph-y) scale))
                       (k// (k/- clipped-right clipped-x) scale)
                       (k// (k/- clipped-bottom clipped-y) scale)
                       color 1.0 0.0))
        (k/= cursor (k/+ cursor (k/* (a/get scene/glyph-advances code) scale)))))))

(a/defn paragraph! :f32
  "Wrap complete text and clip glyphs to the pane without snapping scroll to lines." [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32]
                                                                                      [scale :f32] [top :f32] [bottom :f32] [color :u32]]
  (let [start (k/var (k/usize 0))
        cursor (k/var (k/f32 x))
        row (k/var (k/f32 y))
        line-height (k/* 65.0 scale)]
    (k/while (k/< start (:len text))
      (let [end (k/var (k/usize start))]
        (k/while (and (k/< end (:len text))
                      (k/!= (a/get text end) 32)
                      (k/!= (a/get text end) 10))
          (k/= end (k/+ end 1)))
        (let [word (a/slice text start end)
              width-word (scene/text-width word scale)]
          (when (and (k/> cursor x) (k/> (k/+ cursor width-word) (k/+ x width)))
            (set-state! [row (k/+ row line-height)
                         cursor x]))
          (if (k/> width-word width)
            ;; Long URLs/identifiers have no spaces. Break only between UTF-8
            ;; code points, so their tail remains reachable instead of overflowing.
            (let [glyph-start (k/var (k/usize start))
                  glyph-end (k/var (k/usize start))]
              (k/while (k/< glyph-start end)
                (let [code (scene/glyph-code! text (k/& glyph-end))
                      advance (k/* (a/get scene/glyph-advances code) scale)]
                  (when (and (k/> cursor x) (k/> (k/+ cursor advance) (k/+ x width)))
                    (set-state! [row (k/+ row line-height)
                                 cursor x]))
                  (when (and (k/< row bottom) (k/> (k/+ row (k/* 79.0 scale)) top))
                    (paragraph-text! (a/slice text glyph-start glyph-end)
                                     cursor row scale x top (k/+ x width) bottom color))
                  (set-state! [cursor (k/+ cursor advance)
                               glyph-start glyph-end]))))
            (do
              (when (and (k/< row bottom)
                         (k/> (k/+ row (k/* 79.0 scale)) top))
                (paragraph-text! word cursor row scale x top (k/+ x width) bottom color))
              (k/= cursor (k/+ cursor width-word))))
          (k/= cursor (k/+ cursor (scene/text-width " " scale))))
        (when (and (k/< end (:len text)) (k/== (a/get text end) 10))
          (set-state! [row (k/+ row line-height)
                       cursor x]))
        (k/= start (k/+ end 1))))
    (k/+ row line-height)))

(a/defn note-take! :bool [[id [:slice-const :u8]]]
  (when (or (k/== (:len id) 0) (k/> (:len id) 64))
    (k/return false))
  (let [name (k/var (k/as (mem/zeroes [:array 65 :u8]) [:array 65 :u8]))]
    (k/memcpy (a/slice name 0 (:len id)) id)
    (let [entity (flecs/ecs_lookup scene/world (k/& name))]
      (when (k/== entity 0)
        (k/return false))
      (let [raw (flecs/ecs_get_mut_id scene/world entity scene/passage-component)]
        (when (k/== raw k/null)
          (k/return false))
        (let [state (a/cast raw [:* scene/PassageState])]
          (k/= (:takes state) (k/+ (:takes state) 1))
          true)))))

(a/defn take-action! :u32 []
  (k/atomicRmw :u32 (k/& pending) :.Xchg 0 :.acq_rel))

(a/defn stop-requested? :bool []
  (k/== (k/atomicLoad :u32 (k/& pending) :.acquire) 2))

(a/defn recordable-selection? :bool []
  (and (k/< selected scene/passage-entity-count)
       (k/> (:len (selected-id)) 0)))

(a/defn requested-recording-id [:slice-const :u8] []
  (a/slice record-request-text 0 record-request-length))

(a/defn request-selected-recording! :void []
  (when (or (busy?) (k/! (recordable-selection?)))
    (k/return))
  ;; Keep the clicked passage ID, not a row number that Markdown reload can move.
  (let [id (selected-id)]
    (when (k/> (:len id) 64)
      (k/return))
    (k/= record-request-length (:len id))
    (k/memcpy (a/slice record-request-text 0 record-request-length) id))
  (request! 38)
  (k/= clicked false))

(a/defn- draw-edit-toolbar! :void []
  (when (button! "Tracks" 16.0 104.0 76.0)
    (k/= page 0))
  (when (button! "Script" 100.0 104.0 76.0)
    (k/= page 1))
  (when (button! "<" 184.0 104.0 36.0)
    (set-state! [track-offset (k/- track-offset (k/min track-offset (visible-row-count)))
                 scroll (k/max 0.0 (k/- scroll 180.0))]))
  (when (button! ">" 224.0 104.0 36.0)
    (set-state! [track-offset (k/min (k/+ track-offset (visible-row-count)) (k/- (k/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))
                 scroll (k/min (k/max 0.0 (k/- content-height (editor-y 256.0))) (k/+ scroll 180.0))]))
  (ui-text! "Takes / seconds" 284.0 108.0 0.24 0x586777)
  (when (button! (if follow-playhead
                   "Follow: on"
                   "Follow: off") (right-x 474.0) 104.0 134.0)
    (k/= follow-playhead (k/! follow-playhead)))
  (when (button! "-" (right-x 620.0) 104.0 36.0)
    (zoom-at! 2.0 (timeline-center)))
  (when (button! "+" (right-x 662.0) 104.0 36.0)
    (zoom-at! 0.5 (timeline-center)))
  (when (button! "All" (right-x 704.0) 104.0 58.0)
    (set-state! [timeline-seconds 60.0
                 timeline-start 0.0]))
  (when (button! "<" (right-x 790.0) 104.0 36.0)
    (pan! (k/- (k// timeline-seconds 2.0))))
  (when (button! ">" (right-x 832.0) 104.0 36.0)
    (pan! (k// timeline-seconds 2.0)))
  (when (button! (if routing-visible
                   "Hide routing"
                   "Show routing") (right-x 896.0) 104.0 190.0)
    (show-routing! (k/! routing-visible)))
  (when (inside? (right-x 896.0) 104.0 190.0 28.0)
    (hint! "Show or hide routing. Audio connections and monitoring stay unchanged."))
  (when (and follow-playhead
             (playing-preview?)
             (or (k/< frame-cursor timeline-start)
                 (k/> frame-cursor (k/+ timeline-start (k/* 0.94 timeline-seconds)))))
    (k/= timeline-start (k/max 0.0 (k/min (k/- 60.0 timeline-seconds) (k/- frame-cursor (k/* 0.1 timeline-seconds)))))))

(a/defn timeline-tick-step :f32 [[span :f32] [width :f32]]
  ;; Stable, readable time marks while panning/zooming; not seven arbitrary
  ;; fractions of the current viewport. Keep label density bounded at any width.
  (let [ideal (k// span (k/max 2.0 (k/min 12.0 (k// width 90.0))))]
    (cond
      (k/<= ideal 0.15) 0.1
      (k/<= ideal 0.35) 0.2
      (k/<= ideal 0.75) 0.5
      (k/<= ideal 1.5) 1.0
      (k/<= ideal 3.5) 2.0
      (k/<= ideal 7.5) 5.0
      (k/<= ideal 15.0) 10.0
      (k/<= ideal 35.0) 20.0
      :else 50.0)))

(a/defn- draw-timeline-ruler! :void []
  (when (and (k/== page 0)
             (inside? 242.0 140.0 (k/- (timeline-width) 6.0) 27.0))
    (hint! "Ruler: click or drag to seek")
    (glfw/glfwSetCursor studio-window resize-cursor)
    (when (and clicked (k/! (busy?)))
      (k/= trim-drag 3)
      (position! (time-at (k/floatCast mouse-x)))
      (k/= clicked false)))
  (when (and mix-mode
             (k/> loop-to timeline-start)
             (k/< loop-from (k/+ timeline-start timeline-seconds)))
    (let [x (k/+ 242.0 (k/* (timeline-width) (k/max 0.0 (k// (k/- loop-from timeline-start) timeline-seconds))))
          end-x (k/+ 242.0 (k/* (timeline-width) (k/min 1.0 (k// (k/- loop-to timeline-start) timeline-seconds))))]
      (scene/rect! x 167.0 (k/- end-x x) (editor-y 267.0)
                   (if (:enabled (mixer/loop-state))
                     0xe2f2ee
                     0xe9edf3) 0.0)
      (scene/rect! x 163.0 (k/- end-x x) 3.0 0x356cd5 0.0)))
  (let [step (timeline-tick-step timeline-seconds (timeline-width))
        first (k/* (k/ceil (k// timeline-start step)) step)]
    (dotimes [tick 32]
      (let [seconds (k/+ first (k/* step (k/as (k/floatFromInt tick) :f32)))]
        (when (k/> seconds (k/+ timeline-start timeline-seconds)) (k/break))
        (let [x (pixel-align-x (k/+ 242.0 (k/* (timeline-width) (k// (k/- seconds timeline-start) timeline-seconds))))
              label-x (k/max 242.0 (k/min (k/- (k/+ 242.0 (timeline-width)) 34.0) (k/- x 10.0)))]
          (seconds! seconds label-x 143.0 0.20)
          (scene/rect! x 167.0 1.0 (editor-y 267.0) 0xe0e5ed 0.0))))))

(a/defn- draw-passage-status! :void [[index :u32] [x :f32] [y :f32]]
  (let [status (passage-freshness-status index)
        review? (or (k/== status 2) (k/== status 5))
        recorded? (k/== status 3)
        background (k/u32 (cond
                            review? 0xffeed9
                            recorded? 0xe4f2eb
                            :else 0xe7ebf1))
        foreground (k/u32 (cond
                            review? 0x855017
                            recorded? 0x246147
                            :else 0x46566b))]
    (rounded-rect! x y 76.0 17.0 4.0 background)
    (ui-text! (passage-status-badge status) (k/+ x 6.0) (k/+ y 1.0) 0.19 foreground)
    (when (inside? x y 76.0 17.0)
      (hint! (passage-freshness-label index)))))

(a/defn- draw-track-labels! :void
  [[i :u32] [y :f32] [speaker :u32] [voiced? :bool] [active :bool]]
  (let [background (k/u32 (if active 0xe0ebff 0xf2f4f8))
        accent (k/u32 (if voiced? 0x356cd5 0x8593a8))
        speaker-label (cond
                        (k/== speaker 86) "LA VOITURE"
                        (k/== speaker 77) "LA MANGUE"
                        :else "CONTEXT")]
    (scene/rect! 16.0 y 220.0 54.0 background 0.0)
    (scene/rect! 16.0 y 4.0 54.0 accent 0.0)
    (number! (k/+ i 1) 27.0 (k/+ y 3.0) 0.24)
    (label! speaker-label 48.0 (k/+ y 2.0) 113.0 0x182637)
    (label! (scene/story-text i) 27.0 (k/+ y 34.0) 116.0 0x586777)
    (draw-passage-status! i 150.0 (k/+ y 34.0))))

(a/defn- draw-track-buttons! :void
  [[i :u32] [y :f32] [seconds :f32] [voiced? :bool]]
  (when voiced?
    (when (icon-button! 4 "Arm / disarm this track" 160.0 (k/+ y 1.0) 30.0
                        (k/! (busy?)) (k/== record-track i))
      (set-state! [record-track (if (k/== record-track i)
                                  4294967295
                                  i)
                   selected i
                   clicked false])))
  (when (icon-button! (if (and (k/! mix-mode)
                               (playing-preview?)
                               (k/== preview-node i))
                        1
                        0)
                      "Play / pause this track" 194.0 (k/+ y 1.0) 34.0
                      (and (k/> seconds 0.0) (k/! (busy?))) (and (playing-preview?) (k/== preview-node i)))
    (when (k/!= selected i)
      (k/= seek-seconds 0.0))
    (set-state! [selected i
                 focus-scroll 0.0])
    ;; Row playback is always audition, even when the global transport has REC
    ;; enabled. Resuming here must never schedule a microphone recording.
    (request! 35)
    (k/= clicked false)))

(a/defn- track-row-selection! :void
  [[i :u32] [y :f32] [seconds :f32] [start :f32]]
  (when (and clicked
             (k/! (busy?))
             (k/>= mouse-y y)
             (k/< mouse-y (k/+ y 54.0))
             (k/>= mouse-x 16.0)
             (k/< mouse-x (content-x 878.0)))
    (when (k/!= selected i)
      (k/= seek-seconds 0.0))
    (set-state! [selected i
                 focus-scroll 0.0
                 name-focus false])
    (when (and (k/>= mouse-x 242.0)
               (k/> seconds 0.0)
               (k/>= (time-at (k/floatCast mouse-x)) start)
               (k/< (time-at (k/floatCast mouse-x)) (k/+ start seconds)))
      (k/= take-seconds seconds)
      (position! (time-at (k/floatCast mouse-x)))
      (if double-clicked
        (request! 26)
        (k/= trim-drag 3))))
  (when (and (inside? 242.0 y (k/- (timeline-width) 6.0) 54.0)
             (k/> seconds 0.0))
    (hint! "Click: seek. Double-click: play. Pinch / Option + scroll: zoom.")))

(a/defn- draw-track-waveform! :void
  [[slot :usize] [y :f32] [seconds :f32] [start :f32] [active :bool] [capturing :bool]]
  (when (and (k/> (k/+ start seconds) timeline-start)
             (k/< start (k/+ timeline-start timeline-seconds)))
    (let [a (k/max 0.0 (k// (k/- start timeline-start) timeline-seconds))
          end-ratio (k/min 1.0 (k// (k/- (k/+ start seconds) timeline-start) timeline-seconds))
          w (k/* (timeline-width) (k/- end-ratio a))
          background (k/u32 (cond
                              capturing 0xf9dadd
                              active 0xdce8ff
                              :else 0xe5ebf3))]
      (scene/rect! (k/+ 242.0 (k/* (timeline-width) a)) (k/+ y 2.0) w 50.0 background 0.0)
      (let [peak (k/var (k/f32 0.00001))]
        (when capturing
          (dotimes [b 128]
            (k/= (a/get (:wave (a/get clip-viewport slot)) b)
                 (recorder/wave-bin (capture-fx?) (k/intCast b)))))
        (dotimes [b 128]
          (k/= peak (k/max peak (a/get (:wave (a/get clip-viewport slot)) b))))
        (dotimes [b 128]
          (let [t (k/+ start (k/* seconds (k// (k/as (k/floatFromInt b) :f32) 128.0)))
                x (k/+ 242.0 (k/* (timeline-width) (k// (k/- t timeline-start) timeline-seconds)))
                h (k/* 30.0 (k// (a/get (:wave (a/get clip-viewport slot)) b) peak))]
            (when (and (k/>= x 242.0) (k/< x (content-x 882.0)))
              (scene/rect! x (k/+ y 27.0 (k/- (k// h 2.0))) 2.0 (k/max 1.0 h) 0x4878c7 0.0))))))))

(a/defn- draw-track-playhead! :void [[i :u32] [y :f32] [capturing :bool]]
  (when (and (k/! mix-mode)
             (or (k/== selected i)
                 (and voice-ready (k/== preview-node i))))
    (let [position (if capturing
                     capture-cursor
                     (if (and voice-ready (k/== preview-node i))
                       frame-cursor
                       seek-seconds))
          x (playhead-x position)]
      (when (and (k/>= x 242.0) (k/<= x (content-x 884.0)))
        (scene/rect! x y 2.0 54.0 0x356cd5 0.0)))))

(a/defn- draw-track-row! :void [[data [:* scene/Story]] [slot :usize]]
  (let [i (k/+ track-offset (k/as (k/intCast slot) :u32))
        y (k/+ 169.0 (k/* 56.0 (k/as (k/floatFromInt slot) :f32)))]
    (when (k/< i (:count data))
      (let [node (a/get (:nodes data) i)
            active (k/== selected i)
            capturing (and (k/== capture-phase 2) (capture-passage? i))
            loaded (and (k/== track-snapshot track-offset)
                        (k/< slot track-snapshot-count))
            seconds (if capturing
                      capture-cursor
                      (if loaded
                        (:seconds (a/get clip-viewport slot))
                        0.0))
            start (if (and loaded
                           mix-mode
                           (k/! capturing))
                    (:start (a/get clip-viewport slot))
                    0.0)]
        (draw-track-labels! i y (:speaker node) (k/> (:id_len node) 0) active)
        (draw-track-buttons! i y seconds (k/> (:id_len node) 0))
        (track-row-selection! i y seconds start)
        (draw-track-waveform! slot y seconds start active capturing)
        (when (k/== seconds 0.0)
          (ui-text! (if (k/! loaded)
                      "Loading..."
                      (if (k/> (:id_len node) 0)
                        "Not recorded"
                        "Text only")) 252.0 (k/+ y 11.0) 0.23 0x687787))
        (draw-track-playhead! i y capturing)))))

(a/defn- draw-script-browser! :void [[data [:* scene/Story]]]
  (let [row (k/var (k/f32 (k/- 151.0 scroll)))]
    (dotimes [i (:count data)]
      (let [top row
            node (a/get (:nodes data) i)
            indent (k/* 4.0 (k/as (k/floatFromInt (k/min 12 (:indent node))) :f32))]
        (k/= row (paragraph! (scene/story-text (k/intCast i)) (k/+ 28.0 indent) row (k/- (content-x 834.0) indent) 0.30 150.0 (editor-y 430.0)
                             (if (k/== i selected)
                               0x235ac0
                               0x26364a)))
        (when (and clicked
                   (k/! (busy?))
                   (k/>= mouse-x 16.0)
                   (k/< mouse-x (content-x 884.0))
                   (k/>= mouse-y (k/max top 150.0))
                   (k/< mouse-y (k/min row (editor-y 430.0))))
          (set-state! [selected (k/intCast i)
                       focus-scroll 0.0
                       name-focus false]))
        (k/= row (k/+ row 12.0))))
    (k/= content-height (k/- (k/+ row scroll) 151.0))
    ;; Reflow can shorten the document after widening the window.
    (k/= scroll (k/max 0.0 (k/min scroll (k/max 0.0 (k/- content-height (editor-y 256.0))))))))

(a/defn- draw-timeline-navigation! :void []
  (when mix-mode
    (let [x (playhead-x frame-cursor)]
      (when (and (k/>= x 242.0) (k/<= x (content-x 884.0)))
        (scene/rect! x 167.0 2.0 (editor-y 265.0) 0x356cd5 0.0))))
  (let [w (k/* (timeline-width) (k// timeline-seconds 60.0))
        x (k/+ 242.0 (k/* (timeline-width) (k// timeline-start 60.0)))]
    (scene/rect! 242.0 (editor-y 432.0) (timeline-width) 8.0 0xdce3ed 0.0)
    (scene/rect! x (editor-y 432.0) w 8.0 0x7192c9 0.0)
    (when (inside? 242.0 (editor-y 432.0) (timeline-width) 10.0)
      (hint! "Time scrollbar: drag to pan")
      (glfw/glfwSetCursor studio-window hand-cursor)
      (when clicked
        (set-state! [bar-grab (if (and (k/>= mouse-x x) (k/< mouse-x (k/+ x w)))
                                (k/- (k/as (k/floatCast mouse-x) :f32) x)
                                (k// w 2.0))
                     trim-drag 5]))))
  (let [rows (k/as (k/floatFromInt (visible-row-count)) :f32)
        total (k/as (k/floatFromInt (k/max (visible-row-count) scene/passage-entity-count)) :f32)
        h (k/max 18.0 (k/* (track-height) (k// rows total)))
        y (k/+ 169.0 (k/* (k/- (track-height) h) (k// (k/as (k/floatFromInt track-offset) :f32) (k/max 1.0 (k/- total rows)))))]
    (scene/rect! (content-x 880.0) 169.0 12.0 (track-height) 0xdce3ed 0.0)
    (scene/rect! (content-x 882.0) y 8.0 h 0x7192c9 0.0)
    (when (inside? (content-x 879.0) 169.0 14.0 (track-height))
      (hint! "Tracks: drag or scroll with two fingers")
      (glfw/glfwSetCursor studio-window hand-cursor)
      (when clicked
        (set-state! [bar-grab (if (and (k/>= mouse-y y) (k/< mouse-y (k/+ y h)))
                                (k/- (k/as (k/floatCast mouse-y) :f32) y)
                                (k// h 2.0))
                     trim-drag 6])))))

(a/defn- take-waveform-input! :void []
  (when (k/! (take-editable?))
    (k/return))
  (when (and clicked
             (k/! (busy?))
             (k/>= mouse-x 238.0)
             (k/<= mouse-x (content-x 888.0))
             (k/>= mouse-y (bottom-y 607.0))
             (k/< mouse-y (bottom-y 679.0)))
    (let [x (k/as (k/floatCast mouse-x) :f32)
          a (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-in) :f32)))
          b (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-out) :f32)))]
      (if (k/< (k/min (k/abs (k/- x a)) (k/abs (k/- x b))) 9.0)
        (do
          (k/= trim-drag (if (k/< (k/abs (k/- x a)) (k/abs (k/- x b)))
                           1
                           2))
          (trim-at! x))
        (do
          (k/= trim-drag 4)
          (position! (k/* take-seconds (k/max 0.0 (k/min 1.0 (k// (k/- x 242.0) (timeline-width))))))))))
  (when (inside? 238.0 (bottom-y 607.0) (k/+ (timeline-width) 8.0) 72.0)
    (let [x (k/as (k/floatCast mouse-x) :f32)
          a (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-in) :f32)))
          b (k/+ 242.0 (k/* (k// (timeline-width) 100.0) (k/as (k/floatFromInt trim-out) :f32)))]
      (if (k/< (k/min (k/abs (k/- x a)) (k/abs (k/- x b))) 9.0)
        (do
          (hint! "Edge: trim without changing the source file")
          (glfw/glfwSetCursor studio-window resize-cursor))
        (hint! "Waveform: click or drag to seek")))))

(a/defn- drag-editor-selection! :void []
  ;; Timeline pan/scroll remains available while a take loads. Only the
  ;; selected take's trim edges and waveform seek depend on its upload.
  (when (and (k/! (take-editable?))
             (or (k/== trim-drag 1)
                 (k/== trim-drag 2)
                 (k/== trim-drag 4)))
    (k/= trim-drag 0))
  (when (and (k/> trim-drag 0)
             (or mouse-down clicked)
             (k/! (busy?)))
    (cond
      (k/<= trim-drag 2)
      (trim-at! (k/floatCast mouse-x))
      (k/== trim-drag 3)
      (position! (time-at (k/floatCast mouse-x)))
      (k/== trim-drag 4)
      (position! (k/* take-seconds (k/max 0.0 (k/min 1.0 (k// (k/- (k/as (k/floatCast mouse-x) :f32) 242.0) (timeline-width))))))
      (k/== trim-drag 5)
      (do
        (k/= timeline-start 0.0)
        (pan! (k/* 60.0 (k// (k/- (k/as (k/floatCast mouse-x) :f32) 242.0 bar-grab) (timeline-width)))))
      (k/== trim-drag 6)
      (k/= track-offset (track-offset-at (k/floatCast mouse-y) scene/passage-entity-count))))
  (when (k/! mouse-down)
    (k/= trim-drag 0)))

(a/defn- draw-trim-controls! :void []
  (when (k/> (selected-take-seconds) 0.0)
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

(a/defn- draw-take-editor! :void []
  ;; The editor is always visible: reading a long passage never hides transport or routing.

  (scene/rect! 16.0 editor-top (main-width) (k/- window-height editor-top 40.0) 0xffffff 0.0)
  (let [hover (and (k/== route-menu 0)
                   (inside? 16.0 (k/- editor-top 4.0) (main-width) 8.0))]
    (scene/rect! 16.0 (k/- editor-top 2.0) (main-width) 3.0 (if (or hover divider-drag)
                                                              0x356cd5
                                                              0xc8d1df) 0.0)
    (when (or hover divider-drag)
      (when (k/== vertical-cursor k/null)
        (k/= vertical-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_VRESIZE_CURSOR)))
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
    (k/= focus-scroll (k/max 0.0 (k/- focus-scroll 80.0))))
  (when (button! "Down" (content-x 802.0) (editor-y 447.0) 70.0)
    (k/= focus-scroll (k/min (k/max 0.0 (k/- focus-height (editor-text-height))) (k/+ focus-scroll 80.0))))
  (set-state! [focus-height (k/- (k/+ focus-scroll (paragraph! (scene/story-text selected) 242.0 (k/- (editor-y 489.0) focus-scroll)
                                                               (k/- (timeline-width) 17.0) 0.34 (editor-y 489.0) (bottom-y 594.0) 0x182637)) (editor-y 489.0))
               focus-scroll (k/max 0.0 (k/min focus-scroll (k/max 0.0 (k/- focus-height (editor-text-height)))))])
  (waveform! (bottom-y 611.0))
  (take-waveform-input!)
  (drag-editor-selection!)
  (draw-trim-controls!))

(a/defn draw-edit-workspace! :void []
  (draw-edit-toolbar!)
  (scene/rect! 16.0 143.0 (main-width) (editor-y 291.0) 0xfafbfd 0.0)
  (let [data (k/& (a/get scene/stories scene/active-story))]
    (if (k/== page 0)
      (do
        (draw-timeline-ruler!)
        (dotimes [slot (visible-row-count)]
          (draw-track-row! data slot))
        (draw-timeline-navigation!))
      (when (k/== page 1)
        (draw-script-browser! data))))
  (draw-take-editor!))

(a/defn upload-take-card! :void
  [[slot :u32]
   [available? :bool]
   [missing? :bool]
   [chosen? :bool]
   [duration :f32]
   [text [:slice-const :u8]]]
  (when (k/>= slot 48)
    (k/return))
  (let [card (k/& (a/get take-cards slot))
        length (k/var (k/usize (k/min 127 (:len text))))]
    ;; Truncate only at a UTF-8 boundary; existing take names remain untouched.
    (k/while (and (k/> length 0)
                  (k/< length (:len text))
                  (k/>= (a/get text length) 128)
                  (k/< (a/get text length) 192))
      (k/= length (k/- length 1)))
    (k/= (:available card) available?)
    (k/= (:missing card) missing?)
    (k/= (:chosen card) chosen?)
    (k/= (:seconds card) duration)
    (k/= (:label-length card) length)
    (k/memcpy (a/slice (:label card) 0 length) (a/slice text 0 length))))

(a/defn upload-take-card-bin! :void [[slot :u32] [bin :u32] [peak :f32]]
  (when (and (k/< slot 48) (k/< bin 32))
    (k/= (a/get (:bins (a/get take-cards slot)) bin) peak)))

(a/defn take-card-width :f32 []
  (k/- (k// (timeline-width) 3.0) 8.0))

(a/defn take-card-x :f32 [[column :u32]]
  (k/+ 242.0 (k/* (k// (timeline-width) 3.0) (k/as (k/floatFromInt column) :f32))))

(a/defn- request-take-card! :void [[slot :u32] [audition? :bool]]
  (set-state! [take-grid-action-slot slot
               take-grid-action-revision take-grid-revision])
  (request! (if audition? 37 36))
  (k/= clicked false))

(a/defn- draw-take-card-wave! :void [[card [:* TakeCard]] [x :f32] [y :f32] [width :f32]]
  (let [peak (k/var (k/f32 0.001))]
    (dotimes [i 32]
      (k/= peak (k/max peak (a/get (:bins card) i))))
    (dotimes [i 32]
      (let [height (k/max 1.0 (k/* 19.0 (k// (a/get (:bins card) i) peak)))
            position (k/+ x (k/* (k// width 32.0) (k/as (k/floatFromInt i) :f32)))]
        (scene/rect! position (k/+ y (k/* 0.5 (k/- 20.0 height))) 2.0 height 0x6d8fbe 0.0)))))

(a/defn- draw-take-card! :void [[i :u32] [slot :u32] [column :u32] [y :f32]]
  (let [x (take-card-x column)
        width (take-card-width)
        index (k/+ (k/* slot 3) column)
        card (k/& (a/get take-cards index))
        loaded? (and (k/== take-grid-offset track-offset) (k/< slot take-grid-rows))
        available? (and loaded? (:available card))
        chosen? (and available? (:chosen card) (k/== selected i))
        playing? (and available? (:chosen card) (k/== preview-node i) (playing-preview?))
        border (k/u32 (if chosen? 0x356cd5 0xdce3ed))
        background (k/u32 (if chosen? 0xeaf1ff 0xffffff))]
    (rounded-rect! x y width 54.0 6.0 border)
    (rounded-rect! (k/+ x 1.0) (k/+ y 1.0) (k/- width 2.0) 52.0 5.0 background)
    (if available?
      (do
        (draw-take-card-wave! card (k/+ x 43.0) (k/+ y 6.0) (k/- width 102.0))
        (seconds! (:seconds card) (k/- (k/+ x width) 48.0) (k/+ y 8.0) 0.21)
        (label! (a/slice (:label card) 0 (:label-length card))
                (k/+ x 10.0) (k/+ y 31.0) (k/- width 20.0) 0x26364a)
        (when (icon-button! (if playing? 1 0) "Audition this take" (k/+ x 6.0) (k/+ y 3.0) 32.0
                            (k/! (busy?)) playing?)
          (request-take-card! index true))
        (when (and clicked (k/! (busy?)) (inside? x y width 54.0))
          (request-take-card! index double-clicked)))
      (if (and loaded? (k/! (:missing card))
               (k/< column 2) (k/> (:len (node-id i)) 0))
        (when (icon-button! 4 (if (k/== column 0) "Record dry" "Record FX")
                            (k/+ x 6.0) (k/+ y 12.0) (k/- width 12.0) (k/! (busy?)) false)
          ;; Explicit preparation, not capture. The user still presses Play / REC.
          (set-state! [selected i
                       record-track i
                       record-mode (if (k/== column 0) 1 8)
                       record-scroll 0.0
                       seek-seconds 0.0])
          (select-workspace! 1))
        (label! (cond
                  (k/! loaded?) "Loading..."
                  (:missing card) "Media unavailable"
                  (k/== (:len (node-id i)) 0) "Text only"
                  :else "No favorite yet")
                (k/+ x 12.0) (k/+ y 18.0) (k/- width 24.0) 0x78869a)))))

(a/defn- draw-take-grid-row! :void [[slot :u32]]
  (let [i (k/+ track-offset slot)
        y (k/+ 169.0 (k/* 62.0 (k/as (k/floatFromInt slot) :f32)))]
    (when (k/< i scene/passage-entity-count)
      (let [data (k/& (a/get scene/stories scene/active-story))
            node (a/get (:nodes data) i)]
        (draw-track-labels! i y (:speaker node) (k/> (:id_len node) 0) (k/== selected i)))
      (when (and clicked (k/! (busy?)) (inside? 16.0 y 220.0 54.0))
        (when (k/!= selected i)
          (k/= seek-seconds 0.0))
        (set-state! [selected i
                     focus-scroll 0.0
                     clicked false]))
      (dotimes [column 3]
        (draw-take-card! i slot (k/intCast column) y)))))

(a/defn draw-takes-workspace! :void []
  (ui-text! "TAKES" 24.0 110.0 0.28 0x182637)
  (when (button! "<" 154.0 104.0 32.0)
    (k/= track-offset (k/- track-offset (k/min track-offset (visible-row-count)))))
  (when (button! ">" 194.0 104.0 32.0)
    (k/= track-offset (k/min (k/+ track-offset (visible-row-count))
                             (k/- (k/max (visible-row-count) scene/passage-entity-count) (visible-row-count)))))
  (label! "Select a card to edit. Triangle: audition. Double-click: play."
          250.0 110.0 (k/- (timeline-width) 16.0) 0x586777)
  (dotimes [column 3]
    (ui-text! (cond
                (k/== column 0) "DRY / CLEAN VOICE"
                (k/== column 1) "FX / PROCESSED"
                :else "FAVORITE")
              (k/+ (take-card-x (k/intCast column)) 8.0) 145.0 0.21 0x586777))
  (dotimes [slot (visible-row-count)]
    (draw-take-grid-row! (k/intCast slot)))
  (draw-take-editor!))

(a/defn meter-active? :bool [[input? :bool]]
  (if input?
    (or (recorder/input-check-active?)
        recorder/source-running
        (and recorder/running (k/< recorder/mode 3)))
    (or recorder/monitoring
        (and recorder/running (k/>= recorder/mode 2)))))

(a/defn meter-fraction :f32 [[active? :bool] [peak :f32]]
  ;; The held peak is diagnostic history, not a live signal. Never draw it as one.

  (if active?
    (k/sqrt (k/max 0.0 (k/min 1.0 peak)))
    0.0))

(a/defn live-meter-fraction :f32 [[input? :bool]]
  (meter-fraction (meter-active? input?) (recorder/signal-peak input? false)))

(a/defn counting-in? :bool []
  (k/> countdown-until (glfw/glfwGetTime)))

(a/defn record-guidance [:slice-const :u8] [[phase :u8] [enabled? :bool] [armed? :bool]]
  (cond
    (k/== phase 4)
    "Audio stopped. PCM retained; Stop retries saving."
    (k/== phase 1)
    (if (counting-in?)
      "Count-in running. Stop to cancel."
      "Opening audio devices. Stop to cancel.")
    (k/== phase 2)
    "Recording. Stop to save."
    (k/== phase 3)
    "Capturing FX tail. Saving shortly."
    (k/! armed?)
    "Select and arm a voiced passage."
    enabled?
    "Armed. Play to record."
    :else
    "REC off: enable REC to record."))

(a/defn record-script [:slice-const :u8] []
  (if (showing-capture-script?)
    (a/slice capture-script-text 0 capture-script-length)
    (scene/story-text selected)))

(a/defn armed-passage? :bool []
  (and (k/< record-track scene/passage-entity-count)
       (k/> (:len (node-id record-track)) 0)))

(a/defn record-workspace-guidance [:slice-const :u8]
  [[phase :u8] [voiced? :bool] [playing? :bool] [paused? :bool]]
  (cond
    (k/> phase 0)
    (record-guidance phase true true)

    (k/! voiced?)
    "Text only. Select a voiced passage."

    playing?
    "Listening. Record starts a new take."

    paused?
    "Paused. Record starts a new take."

    :else
    "Record a new take for this passage."))

(a/defn- draw-record-target! :void []
  (if (showing-capture-script?)
    (do
      (ui-text! "CAPTURE TARGET" 266.0 178.0 0.19 0xc63535)
      (label! (record-script) 375.0 175.0 (k/- (timeline-width) 157.0) 0x586777))
    (when (k/< selected scene/passage-entity-count)
      (ui-text! "PASSAGE" 266.0 178.0 0.19 0x586777)
      (number! (k/+ selected 1) 327.0 177.0 0.20)
      (label! (scene/story-text selected)
              357.0 175.0 (k/- (timeline-width) 139.0) 0x586777))))

(a/defn- draw-record-passages! :void []
  (scene/rect! 16.0 104.0 220.0 (k/- window-height 144.0) 0xf2f4f8 0.0)
  (ui-text! "Passages" 28.0 114.0 0.30 0x182637)
  (when (inside? 16.0 104.0 220.0 (k/- window-height 170.0))
    (hint! "Up / Down: select passage. Page Up / Down: move one page. Selection is silent."))
  (when (button! "<" 154.0 110.0 32.0)
    (k/= track-offset (k/- track-offset (k/min track-offset (record-row-count)))))
  (when (button! ">" 194.0 110.0 32.0)
    (k/= track-offset (k/min (k/+ track-offset (record-row-count))
                             (k/- (k/max (record-row-count) scene/passage-entity-count) (record-row-count)))))
  (dotimes [slot (record-row-count)]
    (let [i (k/+ track-offset (k/as (k/intCast slot) :u32))
          y (k/+ 156.0 (k/* 54.0 (k/as (k/floatFromInt slot) :f32)))]
      (when (k/< i scene/passage-entity-count)
        (scene/rect! 16.0 y 220.0 50.0 (if (k/== selected i)
                                         0xe0ebff
                                         0xf2f4f8) 0.0)
        (when (k/== selected i)
          (scene/rect! 16.0 y 3.0 50.0 0x356cd5 0.0))
        (number! (k/+ i 1) 28.0 (k/+ y 4.0) 0.25)
        (label! (scene/story-text i) 51.0 (k/+ y 4.0) 174.0 0x26364a)
        (ui-text! (if (capture-passage? i)
                    "Recording target"
                    (passage-freshness-label i))
                  51.0 (k/+ y 25.0) 0.22 (if (capture-passage? i)
                                           0xc63535
                                           0x586777))
        (when (and clicked
                   (k/! (busy?))
                   (inside? 16.0 y 220.0 50.0))
          (when (k/!= selected i)
            (k/= seek-seconds 0.0))
          (set-state! [selected i
                       record-scroll 0.0
                       focus-scroll 0.0
                       clicked false])))))
  (label! "F2: Edit / Record / Takes" 28.0 (bottom-y 682.0) 194.0 0x586777))

(a/defn- draw-record-script! :void []
  (scene/rect! 266.0 190.0 38.0 2.0 0xadc4e5 0.0)
  (ui-text! "DIALOGUE RECORDING" 266.0 114.0 0.25 0x356cd5)
  (when (icon-button! (if (recorder/input-check-active?) 2 0)
                      (if (recorder/input-check-active?) "Stop check" "Check input")
                      510.0 108.0 132.0 (k/! (busy?)) false)
    (request! 39))
  (when (inside? 510.0 108.0 132.0 30.0)
    (hint! "Check input for 10 seconds. No recording, effects send or speaker playback."))
  (when (button! (if routing-visible
                   "Hide routing"
                   "Show routing") (content-x 724.0) 108.0 148.0)
    (show-routing! (k/! routing-visible)))
  (when (icon-button! 4 "Record new take" 266.0 150.0 156.0
                      (and (k/! (busy?)) (recordable-selection?)) false)
    (request-selected-recording!))
  (label! (if (effects-pass?)
            "Processing saved take. Stop to finish."
            (record-workspace-guidance (capture-phase-value)
                                       (recordable-selection?)
                                       (playing-preview?)
                                       preview-paused))
          438.0 154.0 (k/- (timeline-width) 212.0) 0x586777)
  (draw-record-target!)
  (set-state! [record-text-height (k/- (k/+ record-scroll
                                            (paragraph! (record-script) 266.0 (k/- 204.0 record-scroll)
                                                        (k/- (timeline-width) 48.0) 0.52 204.0 (k/+ 204.0 (record-pane-height)) 0x182637)) 204.0)
               record-scroll (k/max 0.0 (k/min record-scroll (k/max 0.0 (k/- record-text-height (record-pane-height)))))])
  (when (k/> record-text-height (record-pane-height))
    (when (button! "Up" (content-x 724.0) (bottom-y 442.0) 70.0)
      (k/= record-scroll (k/max 0.0 (k/- record-scroll 100.0))))
    (when (button! "Down" (content-x 802.0) (bottom-y 442.0) 70.0)
      (k/= record-scroll (k/min (k/- record-text-height (record-pane-height)) (k/+ record-scroll 100.0))))))

(a/defn- draw-record-meters! :void []
  (rounded-rect! 254.0 (bottom-y 468.0) (k/- (timeline-width) 24.0) 122.0 8.0 0xf6f8fb)
  (ui-text! (if (effects-pass?) "SEND" "INPUT") 266.0 (bottom-y 479.0) 0.24 0x586777)
  (label! (a/slice input-meter-text 0 input-meter-length) 338.0 (bottom-y 479.0) (k/- (timeline-width) 118.0) 0x26364a)
  (scene/rect! 266.0 (bottom-y 510.0) (k/- (timeline-width) 48.0) 10.0 0xdce3ed 0.0)
  (scene/rect! 266.0 (bottom-y 510.0) (k/* (k/- (timeline-width) 48.0)
                                           (live-meter-fraction true)) 10.0 0x258060 0.0)
  (ui-text! "FX RETURN" 266.0 (bottom-y 537.0) 0.24 0x586777)
  (label! (a/slice return-meter-text 0 return-meter-length) 370.0 (bottom-y 537.0) (k/- (timeline-width) 150.0) 0x26364a)
  (scene/rect! 266.0 (bottom-y 566.0) (k/- (timeline-width) 48.0) 10.0 0xdce3ed 0.0)
  (scene/rect! 266.0 (bottom-y 566.0) (k/* (k/- (timeline-width) 48.0)
                                           (live-meter-fraction false)) 10.0 0x356cd5 0.0))

(a/defn- draw-record-audition! :void []
  (ui-text! (if (k/>= capture-phase 2)
              (if (capture-fx?) "LIVE FX RETURN" "LIVE INPUT")
              "TAKE AUDITION")
            254.0 (bottom-y 592.0) 0.19 0x586777)
  (waveform! (bottom-y 611.0))
  (when (and clicked
             (k/! (busy?))
             (selected-waveform?)
             (inside? 242.0 (bottom-y 611.0) (timeline-width) 64.0))
    (position! (k/* take-seconds (k/max 0.0 (k/min 1.0 (k// (k/- (k/as (k/floatCast mouse-x) :f32) 242.0) (timeline-width)))))))
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
                        (and (k/! (busy?)) (k/> (selected-take-seconds) 0.0)) active)
      (request! 35)))
  (when (enabled-button! "Previous take" 424.0 (bottom-y 685.0) 132.0 (and (k/! (busy?)) (k/> (selected-take-seconds) 0.0))
                         "Stop recording and select a saved take first.")
    (request! 4))
  (when (enabled-button! "Next take" 566.0 (bottom-y 685.0) 118.0 (and (k/! (busy?)) (k/> (selected-take-seconds) 0.0))
                         "Stop recording and select a saved take first.")
    (request! 5)))

(a/defn draw-record-workspace! :void []
  (rounded-rect! 16.0 105.0 (main-width) (k/- window-height 144.0) 9.0 0xe0e5ed)
  (rounded-rect! 16.0 104.0 (main-width) (k/- window-height 144.0) 9.0 0xffffff)
  (draw-record-passages!)
  (draw-record-script!)
  (draw-record-meters!)
  (draw-record-audition!))

(a/defn- begin-ui-frame! :void []
  (k/= rendered-frames (k/+ rendered-frames 1))
  ;; The audio callback advances independently; sample once, not once per row.

  (set-state! [frame-cursor (cursor-seconds)
               capture-cursor (if (k/== capture-phase 2)
                                (k// (k/as (k/floatFromInt (recorder/frames-recorded)) :f32) 48000.0)
                                0.0)])
  (glfw/glfwGetCursorPos studio-window (k/& mouse-x) (k/& mouse-y))
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
  (k/= route-click (and (k/> route-menu 0) clicked))
  (when (k/> route-menu 0)
    (k/= clicked false))
  (divider-input!)
  (k/= hint-length 0)
  (glfw/glfwSetCursor studio-window k/null)
  (when (and clicked (k/! (inside? 28.0 (bottom-y 621.0) 192.0 28.0)))
    (when name-focus
      (cancel-name-composition!))
    (k/= name-focus false))
  (when (k/>= selected scene/passage-entity-count)
    (set-state! [selected 0
                 focus-scroll 0.0]))
  (k/= track-offset (k/min track-offset (k/- (k/max 1 scene/passage-entity-count) 1))))

(a/defn- draw-workspace-header! :void []
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
                 (k/== workspace-mode 0) 246.0
                 (k/== workspace-mode 1) 302.0
                 :else 378.0) 36.0
               (cond
                 (k/== workspace-mode 0) 48.0
                 (k/== workspace-mode 1) 68.0
                 :else 62.0) 2.0 0x356cd5 0.0)
  (if mix-mode
    (do
      (when (button! (if (:enabled (mixer/loop-state))
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
        (when (k/! (busy?))
          (k/= audition-boost (k/! audition-boost))
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

(a/defn transport-state-label [:slice-const :u8]
  [[phase :u8]
   [working? :bool]
   [playing? :bool]
   [paused? :bool]
   [record-enabled? :bool]]
  (cond
    (k/== phase 4) "AUDIO STOPPED"
    (k/== phase 3) "FX TAIL"
    (k/== phase 2) "RECORDING"
    (k/== phase 1) (if (counting-in?) "COUNT-IN" "PREPARING")
    working? "WORKING"
    playing? "PLAYING"
    paused? "PAUSED"
    record-enabled? "REC ENABLED"
    :else "STOPPED"))

(a/defn- draw-transport-clock! :void []
  (let [label (transport-state-label (capture-phase-value)
                                     (busy?)
                                     (playing-preview?)
                                     preview-paused
                                     (and (k/== workspace-mode 0) (k/== record-enabled 1)))
        seconds (cond
                  (or (k/== capture-phase 2) (k/== capture-phase 4))
                  capture-cursor
                  (k/== capture-phase 1)
                  (k/as (k/floatCast (k/max 0.0 (k/- countdown-until (glfw/glfwGetTime)))) :f32)
                  :else
                  frame-cursor)]
    (ui-text! label 506.0 44.0 0.20 0x356cd5)
    (seconds! seconds 506.0 65.0 0.30)
    (ui-text! "s" 562.0 67.0 0.23 0x586777)))

(a/defn- draw-recording-options! :void []
  (when (enabled-button! "Count-in" (right-x 602.0) 52.0 112.0 (k/! (busy?)) "Stop before changing count-in.")
    (k/= countdown-seconds (if (k/== countdown-seconds 0)
                             3
                             0)))
  (number! countdown-seconds (right-x 684.0) 56.0 0.24)
  (let [tail-label (cond
                     (k/== tail-seconds 0) "Tail 0 s"
                     (k/== tail-seconds 1) "Tail 1 s"
                     (k/== tail-seconds 3) "Tail 3 s"
                     :else "Tail 5 s")]
    (when (button! tail-label (right-x 724.0) 52.0 78.0)
      (when (k/! (busy?))
        (k/= tail-seconds
             (cond
               (k/== tail-seconds 0) 1
               (k/== tail-seconds 1) 3
               (k/== tail-seconds 3) 5
               :else 0)))))
  (when (button! (if (k/== compensate 1)
                   "Align: on"
                   "Align: off") (right-x 812.0) 52.0 96.0)
    (when (k/! (busy?))
      (k/= compensate (k/- 1 compensate))))
  (when (enabled-button! "Publish to game" (right-x 917.0) 52.0 168.0
                         (take-editable?) "Select a loaded take before publishing.")
    (request! 7)))

(a/defn- draw-transport! :void []
  (let [playing? (playing-preview?)
        focused-record? (k/!= workspace-mode 0)
        record-enabled? (and (k/! focused-record?) (k/== record-enabled 1))
        label (cond
                playing? "PAUSE"
                (paused-preview?) "RESUME"
                record-enabled? "PLAY / REC"
                :else "PLAY")
        enabled? (and (k/! (busy?))
                      (or record-enabled?
                          mix-mode
                          (k/> (selected-take-seconds) 0.0)
                          (paused-preview?)
                          playing?))
        active? playing?]
    (when (icon-button! (if playing? 1 0) label
                        16.0 51.0 130.0 enabled? active?)
      (request! (transport-action))))
  (when (icon-button! 2 "STOP" 154.0 51.0 86.0 true false)
    (k/atomicStore :u32 (k/& pending) 2 :.release))
  (when (icon-button! 3 "Back to start (Home)" 248.0 51.0 42.0 (k/! (busy?)) false)
    (position! 0.0))
  (let [focused? (k/!= workspace-mode 0)]
    (when (icon-button! 4 (if focused? "RECORD" "REC") 301.0 51.0 100.0
                        (and (k/! (busy?)) (or (k/! focused?) (recordable-selection?)))
                        (if focused? (k/> (capture-phase-value) 0) (k/== record-enabled 1)))
      (if focused?
        (request-selected-recording!)
        (request! 34))))
  (when (inside? 301.0 51.0 100.0 28.0)
    (hint! (if (k/!= workspace-mode 0)
             "Record a new take for the selected voiced passage. Stop to save."
             "Enable REC, then Play. Arm a track with its red circle.")))
  (when (button! (record-route-label) 411.0 51.0 87.0)
    (when (k/! (busy?))
      (k/= record-mode (if (k/== record-mode 8)
                         1
                         8))))
  (draw-transport-clock!)
  (draw-recording-options!))

(a/defn monitor-level :u32 []
  (k/min 50 (k/atomicLoad :u32 (k/& recorder/monitor-gain) :.acquire)))

(a/defn set-monitor-level! :void [[percent :u32]]
  (k/atomicStore :u32 (k/& recorder/monitor-gain) (k/min 50 percent) :.release))

(a/defn monitor-level-at :u32 [[pointer-x :f64]]
  (let [fraction (k// (k/- pointer-x (right-x 914.0)) 156.0)
        bounded (k/max 0.0 (k/min 1.0 fraction))]
    (k/intFromFloat (k/+ 0.5 (k/* bounded 50.0)))))

(a/defn update-monitor-level! :void []
  (when (or (k/! routing-visible) (k/> route-menu 0))
    (k/= monitor-level-drag false)
    (k/return))
  (when (and clicked (inside? (right-x 906.0) 497.0 170.0 22.0))
    (k/= monitor-level-drag true))
  (when monitor-level-drag
    (set-monitor-level! (monitor-level-at mouse-x))
    (k/= clicked false)
    (when (k/! mouse-down)
      (k/= monitor-level-drag false))))

(a/defn- draw-monitor-level! :void []
  (let [gain (monitor-level)
        thumb-x (k/+ (right-x 914.0) (k/* 3.12 (k/as (k/floatFromInt gain) :f32)))
        hover? (inside? (right-x 906.0) 497.0 170.0 22.0)]
    (ui-text! "Monitor level" (right-x 908.0) 482.0 0.21 0x586777)
    (number! gain (right-x 1034.0) 482.0 0.21)
    (ui-text! "%" (right-x 1059.0) 482.0 0.21 0x586777)
    (rounded-rect! (right-x 914.0) 506.0 156.0 4.0 2.0 0xdce3ed)
    (when (k/> gain 0)
      (rounded-rect! (right-x 914.0) 506.0 (k/- thumb-x (right-x 914.0)) 4.0 2.0 0x356cd5))
    (rounded-rect! (k/- thumb-x 5.0) 501.0 10.0 14.0 3.0
                   (if (or hover? monitor-level-drag) 0x235ac0 0x356cd5))
    (when (or hover? monitor-level-drag)
      (hint! "Drag to set live headphone monitoring (0-50%). Does not enable monitoring or change takes or playback gain."))))

(a/defvar routing-tools-visible :bool false)

(a/defn show-routing-tools! :void [[visible :bool]]
  (set-state! [routing-tools-visible visible
               clicked false]))

(a/defn routing-controls-available? :bool []
  (and (k/! (busy?)) (k/! (recorder/input-check-active?))))

(a/defn- draw-routing-tools! :void []
  (let [idle? (k/! (busy?))
        route-ready? (routing-controls-available?)]
    (rounded-rect! (right-x 896.0) 524.0 190.0
                   (if routing-tools-visible 194.0 40.0) 8.0 0xf8fafd)
    (when (button! (if routing-tools-visible "Routing tools -" "Routing tools +")
                   (right-x 906.0) 530.0 170.0)
      (show-routing-tools! (k/! routing-tools-visible)))
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
                             (and idle? (k/> (selected-take-seconds) 0.0))
                             "Stop recording and select a saved take to process through the FX route.")
        (request! 3))
      (when (enabled-button! "Recover takes" (right-x 906.0) 690.0 170.0 idle?
                             "Stop recording before recovering interrupted takes.")
        (request! 14)))))

(a/defn- draw-routing-panel! :void []
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
    (label! (a/slice input-meter-text 0 input-meter-length) (right-x 908.0) 204.0 168.0 0x586777)
    (scene/rect! (right-x 908.0) 224.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 224.0 (k/* 168.0 (live-meter-fraction true)) 5.0 0x356cd5 0.0)
    (when (enabled-button! "Send 1/2 > Bitwig..." (right-x 906.0) 234.0 170.0
                           (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 2))
    (label! (recorder/device-name false effects-output) (right-x 908.0) 266.0 166.0 0x586777)
    (when (enabled-button! "FX return 3/4..." (right-x 906.0) 289.0 170.0
                           (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 3))
    (label! (recorder/device-name true return-input) (right-x 908.0) 316.0 166.0 0x586777)
    (label! (a/slice return-meter-text 0 return-meter-length) (right-x 908.0) 338.0 168.0 0x586777)
    (scene/rect! (right-x 908.0) 360.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 360.0 (k/* 168.0 (live-meter-fraction false)) 5.0 0x356cd5 0.0)
    (when (k/== (selected-output-mute) 1)
      (rounded-rect! (right-x 904.0) 384.0 174.0 32.0 5.0 0xd8a34a))
    (when (enabled-button! (if (k/== (selected-output-mute) 1)
                             "Output muted (macOS)"
                             "Listening output...") (right-x 906.0) 386.0 170.0
                           (routing-controls-available?) "Stop recording or the input check before changing devices.")
      (route-open! 4))
    (when (and (k/== (selected-output-mute) 1)
               (inside? (right-x 904.0) 384.0 174.0 60.0))
      (hint! "The selected output is muted in macOS Sound settings. Studio does not change system mute."))
    (label! (recorder/device-name false headphones) (right-x 908.0) 419.0 166.0 0x586777)
    (scene/rect! (right-x 908.0) 440.0 168.0 5.0 0xdce3ed 0.0)
    (scene/rect! (right-x 908.0) 440.0 (k/* 168.0 (k/min 1.0 (k/* 0.00001 (k/as (k/floatFromInt (k/atomicLoad :u32 (k/& playback-level) :.acquire)) :f32)))) 5.0 0x356cd5 0.0)
    (when (enabled-button! (if (k/== monitor-enabled 0)
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

(a/defn- draw-status-bar! :void []
  (label! (if (selected-waveform?)
            (a/slice details 0 details-length)
            "Loading selected take...")
          16.0 (bottom-y 729.0) 520.0 0x356cd5)
  (label! (if (k/> hint-length 0)
            (a/slice hint-text 0 hint-length)
            (a/slice status-text 0 status-length)) 550.0 (bottom-y 729.0) (right-x 535.0) 0x26364a)
  (when (k/> alert-length 0)
    (scene/rect! 0.0 (bottom-y 722.0) window-width 38.0 0xffe7e1 0.0)
    (label! (a/slice alert-text 0 alert-length) 16.0 (bottom-y 730.0) (right-x 1015.0) 0x962f20)
    (when (button! "X" (right-x 1050.0) (bottom-y 726.0) 34.0)
      (k/= alert-length 0))))

(a/defn- draw-device-menu! :void []
  (when (k/> route-menu 0)
    (k/= clicked route-click)
    (when (and clicked (k/! (inside? (right-x 558.0) 137.0 522.0 371.0)))
      (set-state! [route-menu 0
                   clicked false])))
  (when (k/> route-menu 0)
    (let [capture (or (k/== route-menu 1) (k/== route-menu 3))
          total (route-total)
          active (route-selected)]
      (scene/rect! (right-x 558.0) 137.0 522.0 371.0 0xffffff 0.0)
      (ui-text! (if (k/== route-menu 1)
                  "SELECT MICROPHONE / INPUT"
                  (if (k/== route-menu 2)
                    "SEND TO BITWIG (1/2)"
                    (if (k/== route-menu 3)
                      "PROCESSED RETURN (3/4)"
                      "OUTPUT: TAKES / MIX / HEADPHONES"))) (right-x 574.0) 146.0 0.30 0x182637)
      (when (button! "X" (right-x 1030.0) 144.0 34.0)
        (k/= route-menu 0))
      (dotimes [row 8]
        (let [i (k/+ route-offset (k/as (k/intCast row) :u32))
              y (k/+ 187.0 (k/* 32.0 (k/as (k/floatFromInt row) :f32)))]
          (when (k/< i total)
            (let [hover (inside? (right-x 574.0) y 490.0 28.0)]
              (scene/rect! (right-x 574.0) y 490.0 28.0 (if (k/== i active)
                                                          0xdce8ff
                                                          (if hover
                                                            0xe0eaff
                                                            0xeef2f7)) 0.0)
              (when (k/== i route-focus)
                (scene/rect! (right-x 574.0) y 3.0 28.0 0x356cd5 0.0))
              (label! (recorder/device-name capture i) (right-x 584.0) (k/+ y 4.0) 366.0 0x182637)
              (when (k/== i active)
                (ui-text! "Selected" (right-x 983.0) (k/+ y 4.0) 0.22 0x235ac0))
              (when hover
                (glfw/glfwSetCursor studio-window hand-cursor))
              (when (and clicked hover)
                (route-select! i))))))
      (when (k/== total 0)
        (ui-text! "No devices found. Close and reconnect." (right-x 584.0) 197.0 0.24 0x356cd5))
      (ui-text! "Arrows + Enter to select. Escape / outside click to close." (right-x 574.0) 450.0 0.20 0x586777)
      (if (k/> route-offset 0)
        (when (button! "Previous" (right-x 574.0) 477.0 130.0)
          (set-state! [route-offset (k/- route-offset (k/min route-offset 8))
                       route-focus route-offset]))
        (ui-text! "Previous" (right-x 584.0) 482.0 0.24 0x78869a))
      (if (k/< (k/+ route-offset 8) total)
        (when (button! "Next" (right-x 716.0) 477.0 130.0)
          (set-state! [route-offset (k/+ route-offset 8)
                       route-focus route-offset]))
        (ui-text! "Next" (right-x 726.0) 482.0 0.24 0x78869a)))))

(a/defn draw! :void {:attrs #{:export}} []
  (begin-ui-frame!)
  (draw-workspace-header!)
  (draw-transport!)
  (when (k/== workspace-mode 1)
    (draw-record-workspace!))
  (when (k/== workspace-mode 0)
    (draw-edit-workspace!))
  (when (k/== workspace-mode 2)
    (draw-takes-workspace!))
  (draw-routing-panel!)
  (draw-status-bar!)
  ;; Modal input is consumed last, after suppressing underlying clicks.

  (draw-device-menu!))

(a/defstruct FrameTiming {:layout :extern}
              [[interval-ms :f32]
               [build-ms :f32]
               [render-ms :f32]])

(a/defvar frame-timings [:array 240 FrameTiming] (mem/zeroes [:array 240 FrameTiming]))

(a/defvar frame-timing-count :u32 0)

(a/defvar frame-timing-index :u32 0)

(a/defvar frame-previous-start :f64 0.0)

(a/defvar frame-build-ms :f32 0.0)

(a/defn reset-frame-timings! :void []
  (set-state! [frame-timing-count 0
               frame-timing-index 0
               frame-previous-start 0.0
               frame-build-ms 0.0]))

(a/defn record-frame-timing! :void [[started :f64] [finished :f64]]
  (when (k/> frame-previous-start 0.0)
    (k/= (a/get frame-timings frame-timing-index)
         (FrameTiming
          {:interval-ms (k/floatCast (k/* 1000.0 (k/max 0.0 (k/- started frame-previous-start))))
           :build-ms frame-build-ms
           :render-ms (k/floatCast (k/* 1000.0 (k/max 0.0 (k/- finished started))))}))
    (set-state! [frame-timing-index (k/mod (k/+ frame-timing-index 1) 240)
                 frame-timing-count (k/min 240 (k/+ frame-timing-count 1))]))
  (k/= frame-previous-start started))

(a/defn build-frame :u32 {:zig/qualifiers "callconv(.c)"}
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (update-layout!)
  (k/= framebuffer-scale (k// (k/as (k/floatFromInt width) :f32) window-width))
  (k/= :_ height)
  (let [started (glfw/glfwGetTime)
        old-vertices scene/vertices
        old-count scene/vertex-count
        old-width scene/canvas-width
        old-height scene/canvas-height]
    (k/defer (do
               (set-state! [scene/vertices old-vertices
                            scene/vertex-count old-count
                            scene/canvas-width old-width
                            scene/canvas-height old-height])))
    (set-state! [scene/vertices output
                 scene/vertex-count 0
                 scene/canvas-width window-width
                 scene/canvas-height window-height])
    (draw!)
    (k/= frame-build-ms (k/floatCast (k/* 1000.0 (k/- (glfw/glfwGetTime) started))))
    scene/vertex-count))

;; Native game callback: called only by the render loop. REPL callers use the
;; host focus-window! below so AppKit is never entered from an nREPL thread.
(a/defn focus-window-native! :void {:zig/qualifiers "callconv(.c)"} []
  (when (k/!= studio-window k/null)
    (glfw/glfwShowWindow studio-window)
    (glfw/glfwFocusWindow studio-window)))

(a/defn tick-window! :void {:zig/qualifiers "callconv(.c)"} []
  (when (or (k/! attached) (k/== studio-window k/null))
    (k/= frame-previous-start 0.0)
    (k/return))
  ;; Window close hides without discarding a recording. F1 shows it again;
  ;; close! explicitly releases the tool's resources, never the game's.

  (update-layout!)
  (poll-gestures!)

  (when (k/!= (glfw/glfwWindowShouldClose studio-window) 0)
    (k/atomicStore :u32 (k/& pending) 2 :.release)
    (glfw/glfwSetWindowShouldClose studio-window 0)
    (glfw/glfwHideWindow studio-window))
  ;; Keep game audio out of a take even when switching to Bitwig during capture.

  (suppress-game-audio! (or (busy?)
                            (k/!= capture-phase 0)
                            (and (k/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
                                 (k/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)
                                 (k/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_FOCUSED) 0))))
  (when (or (k/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
            (k/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0))
    (cancel-name-composition!)
    (k/= frame-previous-start 0.0)
    (k/return))
  (gpu/swap-context! (k/& renderer))
  (k/defer (gpu/swap-context! (k/& renderer)))
  ;; The existing game watcher publishes first. Refresh this window once for
  ;; that revision, retaining its old pipeline on failure (no per-frame retries).

  (when (k/!= observed-shaders gpu/shader-publications)
    (when (k/! (gpu/reload-shaders!))
      (status! "Studio shader rejected; previous version retained."))
    (k/= observed-shaders gpu/shader-publications))
  (let [started (glfw/glfwGetTime)]
    (k/= frame-build-ms 0.0)
    (when (gpu/render! (k/& build-frame))
      (record-frame-timing! started (glfw/glfwGetTime))))
  (sync-name-input!))

(a/defn reload-assets! :void {:zig/qualifiers "callconv(.c)"} []
  (when attached
    (gpu/swap-context! (k/& renderer))
    (k/defer (gpu/swap-context! (k/& renderer)))
    (gpu/renderer-wait-idle!)
    (gpu/load-atlas!)))

;; GLFW geometry is in screen points, never Retina framebuffer pixels.
(a/defstruct WindowBounds {:layout :extern}
              [[x :i32] [y :i32] [width :i32] [height :i32]
               [left :i32] [top :i32] [right :i32] [bottom :i32] [normal :i32]])

(a/defn window-bounds WindowBounds []
  (let [bounds (k/var (k/as (mem/zeroes WindowBounds) WindowBounds))]
    (when (k/!= studio-window k/null)
      (glfw/glfwGetWindowPos studio-window (k/& (:x bounds)) (k/& (:y bounds)))
      (glfw/glfwGetWindowSize studio-window (k/& (:width bounds)) (k/& (:height bounds)))
      (glfw/glfwGetWindowFrameSize studio-window (k/& (:left bounds)) (k/& (:top bounds))
                                   (k/& (:right bounds)) (k/& (:bottom bounds)))
      (k/= (:normal bounds)
           (if (and (k/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)
                    (k/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_MAXIMIZED) 0))
             1
             0)))
    bounds))

(a/defn monitor-count :u32 []
  (let [count (k/var (k/as 0 :c_int))]
    (k/= :_ (glfw/glfwGetMonitors (k/& count)))
    (k/intCast (k/max 0 count))))

(a/defn monitor-bounds WindowBounds [[index :u32]]
  (let [bounds (k/var (k/as (mem/zeroes WindowBounds) WindowBounds))
        count (k/var (k/as 0 :c_int))
        monitors (glfw/glfwGetMonitors (k/& count))]
    (when (and (k/!= monitors k/null)
               (k/< index (k/as (k/intCast (k/max 0 count)) :u32)))
      (glfw/glfwGetMonitorWorkarea (a/get monitors (k/intCast index))
                                   (k/& (:x bounds)) (k/& (:y bounds))
                                   (k/& (:width bounds)) (k/& (:height bounds))))
    bounds))

(a/defn apply-window-bounds! :void [[x :i32] [y :i32] [width :i32] [height :i32]]
  (when (k/!= studio-window k/null)
    (glfw/glfwSetWindowSize studio-window width height)
    (glfw/glfwSetWindowPos studio-window x y)))

(a/defn window-size-limits! :void []
  (when (k/!= studio-window k/null)
    (glfw/glfwSetWindowSizeLimits studio-window 1100 760 glfw/GLFW_DONT_CARE glfw/GLFW_DONT_CARE)))

(a/defn enable-window-resizing! :void []
  (when (k/!= studio-window k/null)
    ;; GLFW skips native size-limit installation while a window is non-resizable.
    ;; Enable first, then apply limits to already-open development windows too.

    (glfw/glfwSetWindowAttrib studio-window glfw/GLFW_RESIZABLE glfw/GLFW_TRUE)
    (window-size-limits!)))

(a/defn window-resizable? :bool []
  (and (k/!= studio-window k/null)
       (k/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_RESIZABLE) 0)))

(a/defn detach! :void {:zig/qualifiers "callconv(.c)"} []
  (cancel-name-composition!)
  ((:lp_studio_gestures_detach gestures-api))
  (close-playback!)
  (mixer/close!)
  (suppress-game-audio! false)
  (when callbacks-installed
    (k/= :_ (glfw/glfwSetCharCallback studio-window previous-char))
    (k/= :_ (glfw/glfwSetScrollCallback studio-window previous-scroll))
    (k/= :_ (glfw/glfwSetKeyCallback studio-window previous-key))
    (k/= :_ (glfw/glfwSetMouseButtonCallback studio-window previous-mouse))
    (glfw/glfwSetCursor studio-window k/null)
    (glfw/glfwDestroyCursor hand-cursor)
    (glfw/glfwDestroyCursor resize-cursor)
    (glfw/glfwDestroyCursor text-cursor)
    (when (k/!= vertical-cursor k/null)
      (glfw/glfwDestroyCursor vertical-cursor)
      (k/= vertical-cursor k/null))
    (set-state! [hand-cursor k/null
                 resize-cursor k/null
                 text-cursor k/null
                 callbacks-installed false]))
  (when (k/!= studio-window k/null)
    (gpu/swap-context! (k/& renderer))
    (gpu/shutdown-renderer!)
    (gpu/swap-context! (k/& renderer))
    (glfw/glfwDestroyWindow studio-window)
    (k/= studio-window k/null))
  (set-state! [scene/development-tick k/null
               scene/development-shutdown k/null
               scene/development-assets k/null
               scene/development-focus k/null
               attached false]))

(a/defn attach! :void []
  (when (k/== studio-window k/null)
    (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
    (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_TRUE)
    (k/= studio-window (glfw/glfwCreateWindow 1100 760 "La Professeure | Studio" k/null k/null))
    (when (k/== studio-window k/null)
      (k/return))
    (window-size-limits!)
    (gpu/swap-context! (k/& renderer))
    (k/= :_ (gpu/initialize-renderer! studio-window))
    (k/= gpu/lighting 0.0)
    (gpu/swap-context! (k/& renderer))
    (k/= observed-shaders gpu/shader-publications))
  (set-state! [attached true
               scene/development-tick (k/& tick-window!)
               scene/development-shutdown (k/& detach!)
               scene/development-assets (k/& reload-assets!)
               scene/development-focus (k/& focus-window-native!)])
  (when (k/! (install-gestures!))
    (alert! "Trackpad gestures unavailable; Option + scroll still zooms."))
  (glfw/glfwShowWindow studio-window)
  (glfw/glfwFocusWindow studio-window)
  (when (k/! callbacks-installed)
    (set-state! [previous-char (glfw/glfwSetCharCallback studio-window (k/& typed!))
                 previous-scroll (glfw/glfwSetScrollCallback studio-window (k/& scrolled!))
                 previous-key (glfw/glfwSetKeyCallback studio-window (k/& key-event!))
                 previous-mouse (glfw/glfwSetMouseButtonCallback studio-window (k/& mouse-event!))
                 hand-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HAND_CURSOR)
                 resize-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HRESIZE_CURSOR)
                 text-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_IBEAM_CURSOR)
                 callbacks-installed true]))
  (set-state! [event-pressed false
               mouse-down false
               trim-drag 0])
  (status! "Select a passage, then a microphone. Takes stay local."))

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
      (let [{:keys [source fx?]} (render! #(hash-map :source (a/value microphone)
                                                     :fx? (= 8 (a/value record-mode))))]
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
  (try (String. (byte-array (map unchecked-byte (a/value value))) "UTF-8")
       (finally (a/close! value))))

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
   :revision (a/value (node-revision index))
   :speaker (a/value (node-speaker index))
   :text (native-string (scene/story-text index))})

(defn- capture-script-snapshot! [expected-id]
  (render!
   #(let [{:keys [id speaker text] :as passage} (passage-data (a/value selected))]
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
             (render! #(vector (a/value scene/active-story)
                               (a/value scene/passage-entity-count)
                               (a/value track-offset)))
             take-state]]
    (when (not= key @dialogue-display-key)
      (let [rows (render!
                  #(let [offset (a/value track-offset)
                         end (min (a/value scene/passage-entity-count) (+ offset 32))]
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
    (try (a/value value) (finally (a/close! value)))))

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
                      displays (mapv (fn [i] (native-bounds (fn [] (monitor-bounds i)))) (range (a/value (monitor-count))))
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
                                                :routing-visible? (a/value routing-visible) :editor-top (a/value editor-top)))
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
  (let [phase (a/value (capture-phase-value))
        dry? (if (>= phase 2)
               (not (capture-fx?))
               (= 1 (a/value record-mode)))]
    (into {}
          (for [[channel input?] [[:input true] [:return false]]]
            [channel (routing-signal-state
                      channel
                      {:active? (meter-active? input?)
                       :bypassed? (and (not input?) dry?)
                       :source (when (and input? (effects-pass?)) :take)
                       :phase phase
                       :counting-in? (counting-in?)
                       :current-peak (a/value (recorder/signal-peak input? false))
                       :held-peak (a/value (recorder/signal-peak input? true))})]))))

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
                     (format "%.2f" (/ (double (a/value recorder/measured-frames)) 48000.0)) " s. Play or publish.")
                "Take retained, but silent / clipped / invalid audio cannot be published."))
    (when (and (or dry-path alignment-source)
               processed?
               (= 1 (render! #(a/value compensate))))
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
                   (let [frames (a/value (recorder/available-frames (= kind :wet)))]
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
  (let [outputs (render! #(a/value (handle-stopped-outputs!)))]
    (when (pos? outputs)
      (emit-event! {:type :audio/output-stopped :mask outputs})
      (warning! "Listening output stopped. Position retained. Play retries; select another output if needed."))))

(defn- check-audio-devices! []
  (let [mask (a/value (recorder/stopped-device-mask))]
    (when (pos? mask)
      (emit-event! {:type :audio/device-stopped :mask mask})
      (when (pos? (bit-and mask 8))
        (stop-input-check!)
        (warning! "Input check stopped: audio device unavailable. Check input selection and reconnect."))
      (when (pos? (bit-and mask 4))
        (recorder/stop-monitor!)
        (render! #(a/set-value! monitor-enabled 0))
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
  (render! #(do (a/set-value! mix-mode false) (a/set-value! preview-paused false))))

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
      (render! #(do (a/set-value! loop-from 0.0)
                    (a/set-value! loop-to (/ (double (a/value mixer/duration)) 48000.0))))
      {:clips (count sources) :frames (a/value mixer/duration)}
      (catch Throwable e (mixer/reset!) (throw e)))))

(defn- play-mix! []
  (a/set-value! mixer/output-index (render! #(a/value headphones)))
  (when-not (mixer/open!)
    (throw (ex-info "Mix output device could not start" {:code :mix-device})))
  (render! #(do (stop-voice!) (a/set-value! mix-mode true)
                (a/set-value! preview-node (a/value selected)) (a/set-value! preview-paused false)))
  (when (>= (a/value (mixer/cursor-frame)) (a/value mixer/duration))
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
      (render! #(do (stop-voice!) (a/set-value! preview-node 4294967295)
                    (a/set-value! preview-paused false) (a/set-value! seek-seconds 0.0)))
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
      (render! #(a/set-value! audition-source-peak (apply max 0.0 bins)))
      (when-not (render! #(when (play-voice-file! path)
                            (a/set-value! take-seconds (/ frames 48000.0))
                            (a/set-value! preview-node (a/value selected)) (a/set-value! preview-paused false)
                            (seek-preview!) true))
        (throw (ex-info "Audio output unavailable. Select a listening output and try again." {:code :playback-device}))))
    (message! (str "Listening output: " (native-string (recorder/device-name false (render! #(a/value headphones))))))))

(defn publish!
  "Atomically publish a validated dry or processed take; effects are optional."
  []
  (let [id (focused-id)
        {:keys [selected history]} (get @takes id)
        entry (some #(when (= selected (:path %)) %) history)]
    (when-not (and (string? id)
                   (re-matches #"[A-Za-z0-9_-]+" id)
                   (#{:dry :wet} (:kind entry)))
      (throw (ex-info "Select a recorded take before publishing."
                      {:code :take-unavailable})))
    (when-not (recorder/validate-take! selected)
      (throw (ex-info "Cannot publish: silent, clipped or invalid audio."
                      {:code :invalid-audio})))
    (let [destination (io/file "resources/voices" (str id ".wav"))]
      (atomic-write! destination #(io/copy (io/file selected) %))
      (change-takes! "Publish to game" #(assoc-in % [id :published] selected) true)
      (message! "Voice published. F1: listen to the passage in the game.")
      (.getCanonicalPath destination))))

(declare recording-stop-pending?)

(defn- commit-capture-start! [capture]
  ;; Device start can block after the worker's first Stop check. Commit the
  ;; session, visible phase and callback gate in one render task: a native Stop
  ;; already processed by the UI wins, including during the final device open.
  (if (render!
       #(when-not (recording-stop-pending?)
          (reset! session capture)
          (reset! armed nil)
          (begin-capture-presentation!)
          (recorder/release-capture!)
          true))
    (do (begin-recovery!) true)
    (do
      (recorder/stop!)
      ;; Leave Stop queued and the armed request intact for normal cancellation
      ;; acknowledgment. No recovery journal, take entry or PCM was committed.
      false)))

(defn- start-take! [processed?]
  (let [id (native-string (render! #(selected-id)))
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Select a voiced passage with a recording ID" {:id id})))
        script (capture-script-snapshot! id)
        provenance (if processed?
                     (take-provenance id (get-in @takes [id :dry]))
                     (select-keys script [:dialogue-hash]))
        capture (render! #(a/value (if processed?
                                      return-input
                                      microphone)))
        playback (render! #(a/value effects-output))
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
    (recorder/hold-capture!)
    (when-not (recorder/start! capture playback (if processed?
                                                  2
                                                  1) (* 48000 (render! #(a/value tail-seconds))))
      (throw (ex-info "Audio device could not start (or no dry take loaded)" {})))
    (when (commit-capture-start!
           (merge provenance
                  {:id id
                   :script (dissoc script :dialogue-hash)
                   :path (.getCanonicalPath path)
                   :processed? processed?
                   :alignment-source (when processed? (get-in @takes [id :dry]))}))
      (message! (if processed?
                  "Real-time effects pass: Bitwig must return audio on 3/4."
                  "Recording microphone. Stop to save the take.")))))

(defn- start-live-take! []
  (let [id (focused-id)
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Select a passage with a voice ID." {})))
        script (capture-script-snapshot! id)
        capture (render! #(a/value microphone))
        return-index (render! #(a/value return-input))
        send-index (render! #(a/value effects-output))
        stem (str (java.util.UUID/randomUUID))
        dry-path (io/file "build/recording" id (str stem "-dry.wav"))
        wet-path (io/file "build/recording" id (str stem "-wet.wav"))]
    (io/make-parents dry-path)
    (doseq [path [dry-path wet-path]]
      (when-not (.createNewFile path)
        (throw (ex-info "Take path already exists" {:path path}))))
    (when (and (= 1 (render! #(a/value monitor-enabled)))
               (not (recorder/headphone-device? (render! #(a/value headphones)))))
      (throw (ex-info "Connect and select headphones before enabling live monitoring." {})))
    (recorder/hold-capture!)
    (when-not (recorder/start-live! capture return-index send-index (* 48000 (render! #(a/value tail-seconds))))
      (throw (ex-info "Live FX: cannot open the input and BlackHole 16ch." {})))
    (when (commit-capture-start!
           {:id id
            :path (.getCanonicalPath wet-path)
            :script (dissoc script :dialogue-hash)
            :dialogue-hash (:dialogue-hash script)
            :dry-path (.getCanonicalPath dry-path)
            :processed? true
            :live? true})
      (when (= 1 (render! #(a/value monitor-enabled)))
        (when-not (recorder/start-monitor! return-index (render! #(a/value headphones)))
          (throw (ex-info "Headphones unavailable; input stopped, take recoverable." {}))))
      (message! "LIVE + FX: recording input and return; recovery active."))))

(defn- finish-take! []
  (stop-input-check!)
  (stop-mix!)
  (render! #(do (stop-voice!) (a/set-value! preview-node 4294967295)
                (a/set-value! seek-seconds 0.0) (a/set-value! preview-paused false)))
  (cond
    @armed
    (do
      (reset! armed nil)
      (render! #(a/set-value! busy 0))
      (message! "Recording cancelled before capture started."))
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
      (render! #(a/set-value! busy 0)))))

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
                          capture-phase 1
                          countdown-until 0.0])))
  ;; Show PREPARING while initialization runs, rather than leaving an apparently
  ;; stopped transport with disabled controls. Count-in starts only when ready.
  (when-not (recorder/initialize!)
    (throw (ex-info "Audio inputs unavailable. Reconnect before recording." {})))
  (render! #(begin-countdown-clock!))
  (reset! armed {:id id
                 :action action
                 :until (+ (System/currentTimeMillis)
                           (* 1000 (render! #(a/value countdown-seconds))))}))

(defn- schedule! [action]
  (let [id (render!
            #(let [index (a/value record-track)]
               (when (>= index (a/value scene/passage-entity-count))
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

    (render! #(and (= 0 (a/value workspace-mode))
                   (= 1 (a/value record-enabled))))
    (schedule! (render! #(a/value record-mode)))

    :else
    (when-not (render! #(playing-preview?))
      (if (render! #(a/value mix-mode))
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
                                        (let [expected (a/value (recorder/available-frames (= kind :wet)))
                                              end (a/value (recorder/journal! (.getCanonicalPath (io/file dir file)) (= kind :wet) frames))]
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
                   (render! #(a/set-value! audition-source-peak peak))
                   (when-not (render! #(when (play-voice-file! target)
                                         ;; A is not the selected B waveform. Global transport
                                         ;; still controls A, but B must not borrow A's cursor.
                                         (a/set-value! preview-node
                                                        (if (= target path)
                                                          (a/value selected)
                                                          4294967295))
                                         (a/set-value! preview-paused false) (a/set-value! seek-seconds 0.0) true))
                     (throw (ex-info "Playback failed." {})))
                   (swap! comparison assoc :playing-a? next-a?)
                   (refresh-comparison! id)
                   (message! (if next-a?
                               "Playing A"
                               "Playing B"))))
      :trim (let [frames (quot (alength ^bytes (files/pcm path)) 8)
                  from (quot (* frames (render! #(a/value trim-in))) 100)
                  to (quot (* frames (render! #(a/value trim-out))) 100)
                  target (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-trim.wav"))]
              (remember! id (:kind entry) (files/trim! path target from to) entry)
              (message! "Trimmed copy created. Original unchanged."))
      :preferred (do
                   (change-takes! "Favorite take" #(assoc-in % [id :preferred] path))
                   (message! "Favorite take saved.")))))

(defn- device-names [capture?]
  (mapv #(native-string (recorder/device-name capture? %))
        (range (a/value (if capture?
                           recorder/capture-count
                           recorder/playback-count)))))

(defn- routing! [operation]
  (case operation
    :save (let [name (native-string (render! #(entered-name)))
                ins (device-names true)
                outs (device-names false)]
            (when (empty? (.trim name))
              (throw (ex-info "Enter a profile name." {})))
            (swap! presets assoc name {:source (nth ins (render! #(a/value microphone)))
                                       :return (nth ins (render! #(a/value return-input)))
                                       :send (nth outs (render! #(a/value effects-output)))
                                       :headphones (nth outs (render! #(a/value headphones)))
                                       :tail (render! #(a/value tail-seconds))
                                       :countdown (render! #(a/value countdown-seconds))})
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
               (render! #(do (close-playback!) (a/set-value! monitor-enabled 0)
                             (doseq [field [microphone return-input effects-output headphones]]
                               (a/set-value! field 4294967294))
                             (recorder/shutdown!)
                             (when-not (recorder/initialize!)
                               (throw (ex-info "Audio device enumeration failed." {})))))
               (let [ins (device-names true)
                     outs (device-names false)
                     values [(files/resolve-device ins (:source preset)) (files/resolve-device ins (:return preset))
                             (files/resolve-device outs (:send preset)) (files/resolve-device outs (:headphones preset))]]
                 (doseq [[field value] (map vector [microphone return-input effects-output headphones] values)]
                   (render! #(a/set-value! field value)))
                 (render! #(do (a/set-value! tail-seconds (:tail preset)) (a/set-value! countdown-seconds (:countdown preset))
                               (a/set-value! monitor-enabled 0))))
               (message! "Routing reconnected. Live monitoring disabled for safety."))
    :monitor (if (= 1 (render! #(a/value monitor-enabled)))
               (do
                 (recorder/stop-monitor!)
                 (render! #(a/set-value! monitor-enabled 0)))
               (let [index (render! #(a/value headphones))]
                 (when-not (recorder/headphone-device? index)
                   (throw (ex-info "Headphones required. Speakers and loopback are not allowed." {})))
                 (render! #(a/set-value! monitor-enabled 1))
                 (message! (str "Headphone monitoring enabled for the next FX take ("
                                (render! #(a/value (monitor-level))) "%, max 50%)."))))))

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
  (when (= 2 (render! #(a/value workspace-mode)))
    (let [[offset rows ids] (render!
                             #(let [offset (a/value track-offset)
                                    rows (a/value (visible-row-count))
                                    total (a/value scene/passage-entity-count)]
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
            bins (mapv #(a/value (recorder/wave-bin processed? %)) (range 128))]
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
                (a/set-value! take-seconds 0.0)
                (reset-take-name! "")
                (dotimes [i 128]
                  (set-wave! i 0.0))))
        (reset! display-cache key)))))

(defn- refresh-output-health! []
  (let [{:keys [index uid]}
        (render! #(let [index (a/value headphones)]
                    {:index index :uid (native-string (output-device-uid index))}))
        state (a/value (read-output-mute uid))]
    (render! #(update-output-mute! index state))))

(defn- refresh-display! []
  (save-window!)
  (refresh-dialogue-status!)
  (refresh-take-grid!)
  (render!
   #(let [{:keys [input return]} (routing-signal-snapshot)]
      (meter-labels! (:label input) (:label return))))
  ;; Virtualized, fixed-height rows. Decode immutable files off the render thread
  ;; and upload only when the viewport/content changes (never on every meter tick).

  (let [[offset visible ids] (render! #(let [offset (a/value track-offset) count (a/value scene/passage-entity-count)
                                             visible (a/value (visible-row-count))]
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
                      (a/set-value! track-snapshot offset)
                      (a/set-value! track-snapshot-count (count rows))
                      (a/set-value! clip-upload-revision (inc (a/value clip-upload-revision)))))
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
                   (format "REC %.1f s | PCM recovery active | normalized waveform" (/ (a/value (recorder/frames-recorded)) 48000.0)))
                 :else
                 (str (or (:name entry)
                          (when entry
                            (str "Take " (inc (.indexOf (vec (get-in @takes [id :history])) entry)))))
                      " | " (name (or (:kind entry) :none))
                      " | trim " (render! #(a/value trim-in)) "–" (render! #(a/value trim-out)) "%"
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
  (render! #(a/set-value! busy 1))
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
    (finally (render! #(a/set-value! busy 0)))))

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
         (render! #(vec (take (a/value frame-timing-count) (a/value frame-timings)))))))

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
          :mix {:sources @mix-sources :frames (a/value (mixer/cursor-frame)) :duration (a/value mixer/duration)
                :playing? (mixer/playing?)
                :loop (let [v (mixer/loop-state)]
                        (try (let [state (a/value v)]
                               {:from-frame (:from state) :to-frame (:to state) :enabled (:enabled state)})
                             (finally (a/close! v))))}
          :comparison (comparison-state @takes @comparison (focused-id))
          :recording (some-> @session (select-keys [:id :path :processed? :script :dialogue-hash]))
          :countdown (some-> @armed (select-keys [:until :action])) :takes @takes :routing-profile @active-preset}
         (render! #(hash-map :selection (native-string (selected-id))
                             :record-control {:enabled? (= 1 (a/value record-enabled))
                                              :armed-index (a/value record-track)
                                              :mode (if (= 8 (a/value record-mode))
                                                      :fx
                                                      :dry)
                                              :phase (a/value (capture-phase-value))
                                              :count-in-seconds (a/value countdown-seconds)}
                             :audio-focus {:game-suppressed? (game-audio-suppressed?)}
                             :alert (native-string (current-alert))
                             :signal {:input-peak (a/value (recorder/signal-peak true true))
                                      :return-peak (a/value (recorder/signal-peak false true))}
                             :routing-signal (routing-signal-snapshot)
                             :monitoring {:enabled? (= 1 (a/value monitor-enabled))
                                          :level-percent (a/value (monitor-level))
                                          :max-percent 50}
                             :playback {:output-index (a/value playback-output)
                                        :system-mute-state (case (a/value (selected-output-mute))
                                                             1 :muted
                                                             0 :unmuted
                                                             :unknown)
                                        :interrupted? (a/value playback-interrupted)
                                        :audition-gain (a/value audition-gain)
                                        :peak (/ (double (a/value (playback-peak-value))) 1000000.0)
                                        :signal-frames (a/value (playback-signal-count))}
                             :devices {:inputs (mapv (fn [i] (native-string (recorder/device-name true i))) (range (a/value recorder/capture-count)))
                                       :outputs (mapv (fn [i] (native-string (recorder/device-name false i))) (range (a/value recorder/playback-count)))
                                       :selected {:source (a/value microphone) :send (a/value effects-output)
                                                  :return (a/value return-input) :headphones (a/value headphones)}}
                             :transport {:playing? (playing-preview?) :paused? (a/value preview-paused)
                                         :seconds (a/value (cursor-seconds))}
                             :view {:start (a/value timeline-start) :seconds (a/value timeline-seconds)
                                    :mode (some (fn [[mode spec]]
                                                  (when (= (:native-id spec) (a/value workspace-mode)) mode))
                                                workspace-modes)
                                    :routing-visible? (a/value routing-visible)
                                    :routing-tools-visible? (a/value routing-tools-visible)
                                    :editor-top (a/value editor-top) :visible-rows (a/value (visible-row-count))
                                    :loop-selection {:from (a/value loop-from) :to (a/value loop-to)}
                                    :track-offset (a/value track-offset) :follow? (a/value follow-playhead)}))))

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
              (> to-frame (a/value mixer/duration))
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
    (when (a/value mixer/opened)
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
           (range (a/value scene/passage-entity-count)))))

(defn- select-passage! [id]
  (render!
   #(let [index (passage-index id)]
      (when-not index
        (throw (ex-info "Unknown passage" {:code :not-found :id id})))
      (let [rows (if (= 1 (a/value workspace-mode))
                   (a/value (record-row-count))
                   (a/value (visible-row-count)))
            offset (a/value track-offset)
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
      (when (or enabled? (= index (a/value record-track)))
        (a/set-value! record-track (if enabled? index 4294967295))))))

(defn- select-routing! [{:keys [source send return]
                         output-index :headphones
                         :as routing}]
  (render!
   (fn []
     (let [input-count (a/value recorder/capture-count)
           output-count (a/value recorder/playback-count)]
        ;; Validate the complete route before closing an engine or changing fields.
       (when-not (and (< source input-count)
                      (< return input-count)
                      (< send output-count)
                      (< output-index output-count))
         (throw (ex-info "Audio device index is no longer available; refresh devices."
                         {:code :not-found})))
       (when (not= output-index (a/value headphones))
         (close-playback!)
         (mixer/close!)
         (a/set-value! preview-paused false))
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

    (render! #(and (selected-preview?) (a/value preview-paused)))
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
  (render! #(a/set-value! record-enabled (- 1 (a/value record-enabled))))
  (message! (if (= 1 (render! #(a/value record-enabled)))
              "REC ready. Arm a track, select input, then Play."
              "REC disabled. Playback only.")))

(defn- dispatch-command! [op args]
  (case op
    :mix/prepare (prepare-mix!)
    :mix/play (play-mix!)
    :mix/stop (stop-mix!)
    :mix/toggle
    (if (render! #(a/value mix-mode))
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
      (render! #(a/set-value! seek-seconds 0.0))
      (preview!))
    :transport/audition (audition-transport!)

    :selection/passage (select-passage! (:id args))
    :view/zoom (render! #(zoom-at! (:factor args) (a/value (timeline-center))))
    :view/pan (render! #(pan! (:seconds args)))
    :view/routing (render! #(show-routing! (:visible args)))
    :view/routing-tools (render! #(show-routing-tools! (:visible args)))
    :view/editor
    (render!
     #(do
        (a/set-value! divider-drag false)
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

    :record/dry (render! #(a/set-value! record-mode 1))
    :record/fx (render! #(a/set-value! record-mode 8))
    :record/start (schedule-passage! (render! #(a/value record-mode)) (:id args))
    :record/toggle (toggle-record-enabled!)
    :record/enable (render! #(a/set-value! record-enabled (if (:enabled args) 1 0)))
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
        (a/set-value! audition-boost (:enabled args))
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
     :args (render! #(hash-map :slot (a/value take-grid-action-slot)
                               :revision (a/value take-grid-action-revision)
                               :audition (= action 37)))}

    (31 32 33)
    {:op :mix/loop
     :args (render! #(let [v (mixer/loop-state)
                           enabled (try
                                     (:enabled (a/value v))
                                     (finally
                                       (a/close! v)))]
                       {:from (if (= action 32)
                                (a/value (cursor-seconds))
                                (a/value loop-from))
                        :to (if (= action 33)
                              (a/value (cursor-seconds))
                              (a/value loop-to))
                        :enabled (if (= action 31)
                                   (not enabled)
                                   enabled)}))}
    9
    {:op :take/name
     :args {:name (native-string (render! #(entered-name)))}}

    12
    {:op :take/trim
     :args (render! #(hash-map :from (a/value trim-in)
                               :to (a/value trim-out)))}

    (when-let [op (get ui-commands action)]
      {:op op :args {}})))

(defn- process-command! [command ticket]
  ;; Command rejection is not an audio-device failure: never stop an existing capture here.

  (try
    (render! #(a/set-value! busy 1))
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
                    (a/set-value! busy (if @session 1 0))
                    (a/set-value! capture-phase (cond
                                                   (:device-interruption @session) 4
                                                   @session 2
                                                   :else 0))))
        (warning! (:message summary))
        (catch InterruptedException interrupted (throw interrupted))
        (catch Throwable cleanup-error
          (swap! worker-health assoc :cleanup-error (worker-error-summary cleanup-error)))))))

(defn- recording-stop-pending? []
  ;; Do not consume/reorder commands here: the next iteration acknowledges Stop
  ;; normally. A Stop queued during preparation must win over zero count-in.
  (or (stop-requested?)
      (some #(= :transport/stop (get-in % [:command :op])) command-queue)))

(defn- worker-iteration! [refresh?]
  (check-playback-devices!)
  (check-audio-devices!)
  (expire-input-check!)
  ;; Include take-action! in the boundary: resolving this native accessor may
  ;; fail during compilation, before a command has even been consumed.
  (let [action (a/value (take-action!))]
    (if (pos? action)
      (when-let [command (ui-command action)]
        (process-command! command nil))
      (when-let [ticket (.poll command-queue)]
        (process-command! (:command ticket) ticket))))
  (when-let [{:keys [until action id]} @armed]
    (when (and (>= (System/currentTimeMillis) until)
               (not (recording-stop-pending?)))
      (select-recording-target! id)
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
                (a/set-value! busy 0)
                (a/set-value! capture-phase 0))))
  (when refresh?
    (refresh-output-health!)
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
    (when-not (render! #(a/value attached))
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
  (doseq [[capture? field total] [[true return-input (a/value recorder/capture-count)]
                                  [false effects-output (a/value recorder/playback-count)]]
          index (range total)
          :when (= "BlackHole 16ch" (native-string (recorder/device-name capture? index)))]
    (render! #(a/set-value! field index)))
  (render! #(attach!))
  (when-not (render! #(a/value attached))
    (throw (ex-info "Studio window could not open" {})))
  (try (restore-window!)
       (catch Throwable e (warning! (str "Window settings not restored: " (ex-message e)))))
  (reset! window-save-state nil)
  (render! #(a/set-value! page 0))
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
  (render! #(do (detach!) (a/set-value! busy 0) (a/set-value! capture-phase 0)))
  :closed)
