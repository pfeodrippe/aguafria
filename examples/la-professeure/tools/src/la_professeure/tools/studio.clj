(ns la-professeure.tools.studio
  "Native Vulkan recording workspace, attached to the game's existing render thread."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.keyword :as ak] [aguafria.zig :as az]
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
            [clojure.java.io :as io] [clojure.edn :as edn])
  (:import [java.nio.file Files StandardCopyOption CopyOption]))

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
                         (ak/max 1.0 (ak/min 1000.0 (/ 0.2 audition-source-peak))) 1.0))
  (when voice-ready
    (audio/ma_sound_set_volume (ak/& (az/index voices voice-slot)) (* 0.8 audition-gain))))
(az/defn playback-peak-value :- :u32 [] (ak/atomicLoad :u32 (ak/& playback-peak) :.acquire))
(az/defn playback-signal-count :- :u64 [] (ak/atomicLoad :u64 (ak/& playback-signal-frames) :.acquire))

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
(az/defvar route-click :bool false)
(az/defn game-audio-suppressed? :- :bool [] scene/studio-audio-suppressed)

;; Only game's sounds are gated; the studio owns its selected playback device.
;; Preserve the user's M-key mute independently of temporary studio focus.
(az/defn suppress-game-audio! :- :void [[suppressed :bool]]
  (when (ak/== scene/studio-audio-suppressed suppressed) (ak/return))
  (set! scene/studio-audio-suppressed suppressed)
  (when scene/voice-ready
    (audio/ma_sound_set_volume (ak/& (az/index scene/voices scene/voice-slot))
      (if (or scene/audio-muted suppressed) 0.0 0.8)))
  (when scene/audio-ready
    (audio/ma_sound_set_volume (ak/& (az/index scene/tracks scene/active-track))
      (if (or scene/audio-muted suppressed) 0.0 0.35))))

(az/defn stop-voice! :- :void []
  (when voice-ready
    (audio/ma_sound_uninit (ak/& (az/index voices voice-slot)))
    (set! _ (audio/ma_decoder_uninit (ak/& (az/index voice-decoders voice-slot))))
    (set! voice-ready false)))

(az/defn close-playback! :- :void []
  (stop-voice!)
  (when playback-ready
    (audio/ma_engine_uninit (ak/& playback-engine))
    (set! playback-ready false) (set! playback-output 4294967295)))

(az/defn initialize-listen-output! :- :void []
  ;; Never silently send first-run audition into the effects loopback.
  ;; An explicit user selection is preserved, including a virtual output.
  (when (ak/!= headphones 4294967295) (ak/return))
  (dotimes [i recorder/playback-count]
    (let [name (recorder/device-name false (ak/intCast i))]
      (when (and (ak/== (mem/indexOf (az/type :u8) name "BlackHole") ak/null)
                 (ak/== (mem/indexOf (az/type :u8) name "Aggregate") ak/null))
        (when (or (ak/== headphones 4294967295)
                  (ak/!= (az/field (az/index recorder/playback-info i) isDefault) 0))
          (set! headphones (ak/intCast i)))))))

(az/defn prepare-playback! :- :bool []
  (initialize-listen-output!)
  (when (or (ak/! recorder/initialized) (>= headphones recorder/playback-count)) (ak/return false))
  (when (and playback-ready (ak/== playback-output headphones)) (ak/return true))
  (close-playback!)
  (let [^:var config ((az/field recorder/api ma_engine_config_init))]
    (set! (az/field config pContext) (ak/& recorder/context))
    (set! (az/field config pPlaybackDeviceID) (ak/& (az/field (az/index recorder/playback-info headphones) id)))
    (set! (az/field config channels) 2)
    (set! (az/field config sampleRate) 48000)
    (set! (az/field config onProcess) (ak/& playback-process!))
    (when (ak/!= ((az/field recorder/api ma_engine_init) (ak/& config) (ak/ptrCast (ak/& playback-engine))) 0)
      (ak/return false)))
  (set! playback-ready true) (set! playback-output headphones) true)

(az/defn play-voice-file! :- :bool [[path [:slice-const :u8]]]
  (when (or (ak/! (prepare-playback!)) (ak/== (az/field path len) 0)
            (>= (az/field path len) 4096)) (ak/return false))
  (let [^:var filename (mem/zeroes (az/type [:array 4096 :u8]))
        next (mod (+ voice-slot 1) 2)
        decoder (ak/& (az/index voice-decoders next)) candidate (ak/& (az/index voices next))]
    (dotimes [i (az/field path len)] (when (ak/== (az/index path i) 0) (ak/return false)))
    (ak/memcpy (az/slice filename 0 (az/field path len)) path)
    (when (ak/!= (audio/ma_decoder_init_file (ak/& filename) ak/null decoder) audio/MA_SUCCESS) (ak/return false))
    (when (ak/!= (audio/ma_sound_init_from_data_source (ak/& playback-engine)
                  (ak/as (az/type [:* audio/ma_data_source]) (ak/ptrCast decoder))
                  0 ak/null candidate) audio/MA_SUCCESS)
      (set! _ (audio/ma_decoder_uninit decoder)) (ak/return false))
    (update-audition-gain!)
    (audio/ma_sound_set_looping candidate 0) (audio/ma_sound_set_volume candidate (* 0.8 audition-gain))
    (ak/atomicStore :u32 (ak/& playback-peak) 0 :.release)
    (ak/atomicStore :u64 (ak/& playback-signal-frames) 0 :.release)
    (when (ak/!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate) (set! _ (audio/ma_decoder_uninit decoder)) (ak/return false))
    (stop-voice!) (set! voice-slot next) (set! voice-ready true) true))
(az/defvar scroll :f32 0.0)
(az/defvar focus-scroll :f32 0.0)
(az/defvar content-height :f32 0.0)
(az/defvar focus-height :f32 0.0)
(az/defvar mouse-x :f64 0.0)
(az/defvar mouse-y :f64 0.0)
(az/defvar mouse-down false)
(az/defvar clicked false)
(az/defvar pending :u32 0)
(az/defvar busy :u8 0)
(az/defvar capture-phase :u8 0)
(az/defvar record-enabled :u8 0)
(az/defvar record-track :u32 4294967295)
(az/defvar record-mode :u8 1)
(az/defvar countdown-until :f64 0.0)
(az/defvar capture-cursor :f32 0.0)
(az/defvar status-text [:array 256 :u8] (mem/zeroes (az/type [:array 256 :u8])))
(az/defvar status-length :usize 0)
(az/defvar microphone :u32 0)
(az/defvar return-input :u32 0)
(az/defvar effects-output :u32 0)
(az/defvar test-click false)
(az/defvar test-x :f64 0.0)
(az/defvar test-y :f64 0.0)
(az/defvar page :u32 0)
(az/defvar tail-seconds :u32 1)
(az/defvar countdown-seconds :u32 0)
(az/defn begin-countdown-clock! :- :void []
  (set! countdown-until (+ (glfw/glfwGetTime) (ak/as :f64 (ak/floatFromInt countdown-seconds)))))
(az/defvar monitor-enabled :u8 0)
(az/defvar compensate :u8 1)
(az/defvar trim-in :u32 0)
(az/defvar trim-out :u32 100)
(az/defvar name-focus false)
(az/defvar back-down false)
(az/defvar edit-name [:array 128 :u8] (mem/zeroes (az/type [:array 128 :u8])))
(az/defvar name-length :usize 0)
(az/defvar details [:array 512 :u8] (mem/zeroes (az/type [:array 512 :u8])))
(az/defvar details-length :usize 0)
(az/defvar wave [:array 128 :f32] (mem/zeroes (az/type [:array 128 :f32])))
(az/defvar track-offset :u32 0)
(az/defvar track-snapshot :u32 4294967295)
(az/defvar clip-seconds [:array 6 :f32] (mem/zeroes (az/type [:array 6 :f32])))
(az/defvar clip-start [:array 6 :f32] (mem/zeroes (az/type [:array 6 :f32])))
(az/defvar clip-wave [:array 768 :f32] (mem/zeroes (az/type [:array 768 :f32])))
(az/defvar clip-count [:array 6 :u32] (mem/zeroes (az/type [:array 6 :u32])))
(az/defvar take-seconds :f32 0.0)
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
(az/defvar text-cursor [:optional [:* glfw/GLFWcursor]] ak/null)
(az/defvar hint-text [:array 256 :u8] (mem/zeroes (az/type [:array 256 :u8])))
(az/defvar hint-length :usize 0)
(az/defvar rendered-frames :u64 0)

(az/defn hint! :- :void [[text [:slice-const :u8]]]
  (set! hint-length (ak/min 255 (az/field text len)))
  (ak/memcpy (az/slice hint-text 0 hint-length) (az/slice text 0 hint-length)))
(az/defn inside? :- :bool [[x :f32] [y :f32] [w :f32] [h :f32]]
  (and (>= mouse-x x) (< mouse-x (+ x w)) (>= mouse-y y) (< mouse-y (+ y h))))
(az/defn playing-preview? :- :bool []
  (if mix-mode (mixer/playing?)
    (and voice-ready (< preview-node 1024)
         (ak/!= (audio/ma_sound_is_playing (ak/& (az/index voices voice-slot))) 0))))
(az/defn pause-preview! :- :void []
  (when (playing-preview?)
    (if mix-mode (mixer/pause!) (set! _ (audio/ma_sound_stop (ak/& (az/index voices voice-slot)))))
    (set! preview-paused true)))
(az/defn resume-preview! :- :bool []
  (when mix-mode
    (when (>= (mixer/cursor-frame) mixer/duration) (mixer/seek! 0))
    (mixer/play!) (set! preview-paused false) (ak/return true))
  (when (or (ak/! voice-ready) (ak/! preview-paused) (ak/!= preview-node selected)) (ak/return false))
  (set! preview-paused false)
  (ak/== (audio/ma_sound_start (ak/& (az/index voices voice-slot))) audio/MA_SUCCESS))
(az/defn position! :- :void [[seconds :f32]]
  (when mix-mode (mixer/seek! (ak/intFromFloat (* 48000.0 (ak/max 0.0 seconds)))) (ak/return))
  (set! seek-seconds (ak/max 0.0 (ak/min take-seconds seconds)))
  (when (and voice-ready (ak/== preview-node selected)) (seek-preview!)))
(az/defn pan! :- :void [[seconds :f32]]
  (set! timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (+ timeline-start seconds))))
  (set! follow-playhead false))
(az/defn track-offset-at :- :u32 [[y :f32] [count :u32]]
  (let [total (ak/as :f32 (ak/floatFromInt (ak/max 6 count)))
        height (ak/max 18.0 (* 262.0 (/ 6.0 total)))
        fraction (ak/max 0.0 (ak/min 1.0 (/ (- y 169.0 bar-grab) (ak/max 1.0 (- 262.0 height)))))]
    (ak/intFromFloat (+ 0.5 (* (- total 6.0) fraction)))))
(az/defn zoom-at! :- :void [[factor :f32] [x :f32]]
  (let [anchor (time-at x) ratio (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) 642.0)))]
    (zoom! factor)
    (set! timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (- anchor (* ratio timeline-seconds))))))
  (set! follow-playhead false))
(az/defn scroll-by! :- :void [[dx :f32] [dy :f32] [zoom? :bool] [horizontal? :bool]]
  (cond
    (inside? 242.0 489.0 642.0 105.0)
    (set! focus-scroll (ak/max 0.0 (ak/min (ak/max 0.0 (- focus-height 96.0)) (- focus-scroll (* dy 20.0)))))
    (inside? 16.0 143.0 868.0 299.0)
    (cond
      (and (ak/== page 0) zoom?) (zoom-at! (ak/exp (* -0.12 dy)) (ak/floatCast mouse-x))
      (and (ak/== page 0) horizontal?) (pan! (* -0.04 dy timeline-seconds))
      :else
      (do
        (when (ak/!= dx 0.0) (pan! (* -0.04 dx timeline-seconds)))
        (if (ak/== page 1)
          (set! scroll (ak/max 0.0 (ak/min (ak/max 0.0 (- content-height 256.0)) (- scroll (* dy 22.0)))))
          (do
            (when (ak/!= (ak/as :u32 (ak/intFromFloat track-scroll)) track-offset)
              (set! track-scroll (ak/floatFromInt track-offset)))
            (set! track-scroll (ak/max 0.0 (ak/min (ak/as :f32 (ak/floatFromInt (- (ak/max 6 scene/passage-entity-count) 6))) (- track-scroll dy))))
            (set! track-offset (ak/intFromFloat track-scroll))))))))
(az/defn scrolled! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [dx :f64] [dy :f64]]
  (if attached
    (do (glfw/glfwGetCursorPos window (ak/& mouse-x) (ak/& mouse-y))
        (scroll-by! (ak/floatCast dx) (ak/floatCast dy)
          (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_ALT) glfw/GLFW_PRESS)
              (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_ALT) glfw/GLFW_PRESS))
          (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT_SHIFT) glfw/GLFW_PRESS)
              (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT_SHIFT) glfw/GLFW_PRESS))))
    (when (ak/!= previous-scroll ak/null) ((az/unwrap previous-scroll) window dx dy))))
(az/defn mouse-event! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [button :c_int] [action :c_int] [mods :c_int]]
  (when (and attached (ak/== button glfw/GLFW_MOUSE_BUTTON_LEFT))
    (set! mouse-down (ak/== action glfw/GLFW_PRESS))
    (when mouse-down
      (glfw/glfwGetCursorPos window (ak/& press-x) (ak/& press-y))
      (let [now (glfw/glfwGetTime)]
        (set! event-double (and (< (- now last-press-time) 0.32)
                               (< (ak/abs (- press-x last-press-x)) 5.0) (< (ak/abs (- press-y last-press-y)) 5.0)))
        (set! last-press-time now) (set! last-press-x press-x) (set! last-press-y press-y))
      (set! event-pressed true)))
  (when (and (ak/! attached) (ak/!= previous-mouse ak/null))
    ((az/unwrap previous-mouse) window button action mods)))
(az/defn key-event! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [key :c_int] [scancode :c_int] [action :c_int] [mods :c_int]]
  (when (> route-menu 0)
    (when (and (ak/== key glfw/GLFW_KEY_ESCAPE) (ak/== action glfw/GLFW_PRESS)) (set! route-menu 0))
    (ak/return))
  (when (and attached (ak/!= action glfw/GLFW_RELEASE))
    (if name-focus
      (cond
        (ak/== key glfw/GLFW_KEY_ESCAPE) (set! name-focus false)
        (ak/== key glfw/GLFW_KEY_ENTER) (do (set! name-focus false) (request! 9))
        (ak/== key glfw/GLFW_KEY_BACKSPACE)
        (when (> name-length 0)
          (set! name-length (- name-length 1))
          (ak/while (and (> name-length 0) (>= (az/index edit-name name-length) 128) (< (az/index edit-name name-length) 192))
            (set! name-length (- name-length 1)))))
      (when (ak/== action glfw/GLFW_PRESS)
        (cond
          (and (ak/== key glfw/GLFW_KEY_Z)
               (ak/!= (& mods (| glfw/GLFW_MOD_SUPER glfw/GLFW_MOD_CONTROL)) 0))
          (request! (if (ak/!= (& mods glfw/GLFW_MOD_SHIFT) 0) 28 27))
          (ak/== key glfw/GLFW_KEY_SPACE) (if (busy?) (ak/atomicStore :u32 (ak/& pending) 2 :.release) (request! 6))
          (ak/== key glfw/GLFW_KEY_F1) (glfw/glfwFocusWindow scene/window)
          (ak/== key glfw/GLFW_KEY_ESCAPE) (ak/atomicStore :u32 (ak/& pending) 2 :.release)
          (ak/== key glfw/GLFW_KEY_HOME) (when (ak/! (busy?)) (position! 0.0))
          (ak/== key glfw/GLFW_KEY_EQUAL) (zoom-at! 0.8 563.0)
          (ak/== key glfw/GLFW_KEY_MINUS) (zoom-at! 1.25 563.0)))))
  (when (and (ak/! attached) (ak/!= previous-key ak/null))
    ((az/unwrap previous-key) window key scancode action mods)))

(az/defn node-id :- [:slice-const :u8] [[index :u32]]
  (let [node (ak/& (az/index (az/field (az/index scene/stories scene/active-story) nodes) index))]
    (az/slice (az/field node id) 0 (az/field node id_len))))
(az/defn clip! :- :void [[slot :u32] [seconds :f32] [count :u32]]
  (when (< slot 6) (set! (az/index clip-seconds slot) seconds) (set! (az/index clip-count slot) count)))
(az/defn clip-start! :- :void [[slot :u32] [seconds :f32]]
  (when (< slot 6) (set! (az/index clip-start slot) seconds)))
(az/defn clip-bin! :- :void [[slot :u32] [bin :u32] [value :f32]]
  (when (and (< slot 6) (< bin 128)) (set! (az/index clip-wave (+ (* slot 128) bin)) value)))
(az/defn time-at :- :f32 [[x :f32]]
  (+ timeline-start (* (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) 642.0))) timeline-seconds)))
(az/defn zoom! :- :void [[factor :f32]]
  (set! timeline-seconds (ak/max 2.0 (ak/min 60.0 (* timeline-seconds factor))))
  (set! timeline-start (ak/min timeline-start (- 60.0 timeline-seconds))))
(az/defn trim-at! :- :void [[x :f32]]
  (let [^{:zig/type :u32} percent (ak/intFromFloat (ak/max 0.0 (ak/min 100.0 (* 100.0 (/ (- x 242.0) 642.0)))))]
    (when (ak/== trim-drag 1) (set! trim-in (ak/min (- trim-out 1) percent)))
    (when (ak/== trim-drag 2) (set! trim-out (ak/max (+ trim-in 1) percent)))))
(az/defn cursor-seconds :- :f32 []
  (when mix-mode (ak/return (/ (ak/as :f32 (ak/floatFromInt (mixer/cursor-frame))) 48000.0)))
  (when (ak/! voice-ready) (ak/return 0.0))
  (let [^{:var :u64} cursor 0]
    (set! _ (audio/ma_sound_get_cursor_in_pcm_frames
              (ak/& (az/index voices voice-slot)) (ak/& cursor)))
    (/ (ak/as :f32 (ak/floatFromInt cursor)) 48000.0)))
(az/defn playhead-x :- :f32 [[seconds :f32]]
  (let [raw (+ 242.0 (* 642.0 (/ (- seconds timeline-start) timeline-seconds)))
        scale (ak/max 1.0 framebuffer-scale)]
    (/ (ak/floor (+ 0.5 (* raw scale))) scale)))
(az/defn seek-preview! :- :void []
  (when voice-ready
    (set! _ (audio/ma_sound_seek_to_pcm_frame
      (ak/& (az/index voices voice-slot))
      (ak/intFromFloat (* 48000.0 (ak/max 0.0 (ak/min take-seconds seek-seconds))))))))
(az/defn number! :- :void [[value :u32] [x :f32] [y :f32] [scale :f32]]
  (let [^{:var [:array 10 :u8]} digits ak/undefined ^{:var :usize} start 10 ^{:var :u32} n value]
    (ak/while true
      (set! start (- start 1)) (set! (az/index digits start) (ak/intCast (+ 48 (mod n 10))))
      (set! n (/ n 10)) (when (ak/== n 0) (ak/break)))
    (scene/text! (az/slice digits start 10) x y scale 0xd7d2c5)))
(az/defn label! :- :void [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [color :u32]]
  (let [^{:var :usize} end (az/field text len)]
    (ak/while (and (> end 0) (> (scene/text-width (az/slice text 0 end) 0.24) width))
      (set! end (- end 1))
      (ak/while (and (> end 0) (>= (az/index text end) 128) (< (az/index text end) 192))
        (set! end (- end 1))))
    (scene/text! (az/slice text 0 end) x y 0.24 color)))
(az/defn seconds! :- :void [[seconds :f32] [x :f32] [y :f32] [scale :f32]]
  (let [^{:zig/type :u32} tenths (ak/intFromFloat (+ 0.5 (* 10.0 (ak/max 0.0 seconds))))
        ^{:var [:array 12 :u8]} digits ak/undefined ^{:var :usize} start 10
        ^{:var :u32} n (/ tenths 10)]
    (set! (az/index digits 10) 46) (set! (az/index digits 11) (ak/intCast (+ 48 (mod tenths 10))))
    (ak/while true
      (set! start (- start 1)) (set! (az/index digits start) (ak/intCast (+ 48 (mod n 10))))
      (set! n (/ n 10)) (when (ak/== n 0) (ak/break)))
    (scene/text! (az/slice digits start 12) x y scale 0xd7d2c5)))

(az/defn details! :- :void [[text [:slice-const :u8]]]
  (set! details-length (ak/min 511 (az/field text len)))
  (ak/memcpy (az/slice details 0 details-length) (az/slice text 0 details-length)))
(az/defn name! :- :void [[text [:slice-const :u8]]]
  (set! name-length (ak/min 120 (az/field text len)))
  (ak/memcpy (az/slice edit-name 0 name-length) (az/slice text 0 name-length)))
(az/defn entered-name :- [:slice-const :u8] [] (az/slice edit-name 0 name-length))
(az/defn set-wave! :- :void [[index :u32] [value :f32]]
  (when (< index 128) (set! (az/index wave index) value)))
(az/defn typed! {:zig/qualifiers "callconv(.c)"} :- :void
  [[window [:optional [:* glfw/GLFWwindow]]] [cp :u32]]
  (when (and (ak/! attached) (ak/!= previous-char ak/null))
    ((az/unwrap previous-char) window cp))
  (when (and attached name-focus (ak/! (busy?)) (>= cp 32) (ak/!= cp 127)
             (<= cp 1114111) (or (< cp 55296) (> cp 57343)) (< name-length 120))
    (let [^{:zig/type :usize} n (if (< cp 128) 1 (if (< cp 2048) 2 (if (< cp 65536) 3 4)))]
      (set! (az/index edit-name name-length)
            (ak/intCast (if (ak/== n 1) cp (if (ak/== n 2) (+ 192 (/ cp 64))
                         (if (ak/== n 3) (+ 224 (/ cp 4096)) (+ 240 (/ cp 262144)))))))
      (dotimes [i (- n 1)]
        (let [^{:zig/type :u32} divisor (if (ak/== (- n i) 4) 4096 (if (ak/== (- n i) 3) 64 1))]
          (set! (az/index edit-name (+ name-length i 1)) (ak/intCast (+ 128 (mod (/ cp divisor) 64))))))
      (set! name-length (+ name-length n)))))

(az/defn waveform! :- :void [[y :f32]]
  (scene/rect! 242.0 y 642.0 65.0 0x111619 0.0)
  (let [^{:var :f32} maximum 0.00001]
    (dotimes [i 128] (set! maximum (ak/max maximum (az/index wave i))))
    (dotimes [i 128]
    (let [height (* 55.0 (/ (az/index wave i) maximum))
          x (+ 244.0 (* 5.0 (ak/as :f32 (ak/floatFromInt i))))]
      (scene/rect! x (+ y (/ (- 65.0 height) 2.0)) 2.0 (ak/max 1.0 height)
                   (if (or (< (* i 100) (* trim-in 128)) (> (* i 100) (* trim-out 128))) 0x54584b 0xb99668) 0.0))))
  (scene/rect! (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-in)))) y 3.0 65.0 0xf2b967 0.0)
  (scene/rect! (ak/min 881.0 (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-out))))) y 3.0 65.0 0xf2b967 0.0))

(az/defn click-at!
  "Development QA input, consumed by the exact same hit-testing as physical clicks."
  :- :void [[x :f64] [y :f64]]
  (set! test-x x) (set! test-y y) (set! test-click true))
(az/defn busy? :- :bool [] (ak/!= (ak/atomicLoad :u8 (ak/& busy) :.acquire) 0))

(az/defn status! :- :void [[text [:slice-const :u8]]]
  (set! status-length (ak/min 255 (az/field text len)))
  (ak/memcpy (az/slice status-text 0 status-length) (az/slice text 0 status-length)))

(az/defn button! :- :bool [[label [:slice-const :u8]] [x :f32] [y :f32] [w :f32]]
  (let [hover (and (>= mouse-x x) (< mouse-x (+ x w)) (>= mouse-y y) (< mouse-y (+ y 28.0)))]
    (scene/rect! x y w 28.0 (if hover 0x56564d 0x373d42) 0.0)
    (label! label (+ x 8.0) (+ y 4.0) (- w 12.0) 0xece3ce)
    (when hover (glfw/glfwSetCursor studio-window hand-cursor) (hint! label))
    (and hover clicked)))

(az/defn icon-button! :- :bool [[kind :u32] [label [:slice-const :u8]] [x :f32] [y :f32]
                              [w :f32] [enabled :bool] [active :bool]]
  (let [hover (inside? x y w 30.0) ^{:zig/type :u32} color (if enabled 0xf3e8d2 0x727b82)]
    (scene/rect! x y w 30.0 (if (ak/! enabled) 0x252b30 (if active 0x796139 (if hover 0x586259 0x3d4944))) 0.0)
    (cond
      (ak/== kind 0)
      (dotimes [row 9]
        (scene/rect! (+ x 9.0) (+ y 6.0 (* 2.0 (ak/as :f32 (ak/floatFromInt row))))
          (- 13.0 (* 2.5 (ak/abs (- (ak/as :f32 (ak/floatFromInt row)) 4.0)))) 2.0 color 0.0))
      (ak/== kind 1) (do (scene/rect! (+ x 9.0) (+ y 7.0) 4.0 16.0 color 0.0)
                         (scene/rect! (+ x 18.0) (+ y 7.0) 4.0 16.0 color 0.0))
      (ak/== kind 2) (scene/rect! (+ x 9.0) (+ y 8.0) 13.0 13.0 color 0.0)
      (ak/== kind 3) (do (scene/rect! (+ x 7.0) (+ y 7.0) 3.0 16.0 color 0.0)
                         (dotimes [row 9]
                           (let [width (- 12.0 (* 2.5 (ak/abs (- (ak/as :f32 (ak/floatFromInt row)) 4.0))))]
                             (scene/rect! (- (+ x 24.0) width) (+ y 6.0 (* 2.0 (ak/as :f32 (ak/floatFromInt row)))) width 2.0 color 0.0))))
      (ak/== kind 4)
      (dotimes [row 16]
        (let [dy (- (+ (ak/as :f32 (ak/floatFromInt row)) 0.5) 8.0)
              half (ak/sqrt (ak/max 0.0 (- 64.0 (* dy dy))))]
          (scene/rect! (- (+ x 16.0) half) (+ y 7.0 (ak/as :f32 (ak/floatFromInt row)))
                       (* half 2.0) 1.0 (if (or enabled active) 0xf06d62 0x727b82) 0.0)
          (when (and (ak/! active) (< (ak/abs dy) 5.0))
            (let [inner (ak/sqrt (ak/max 0.0 (- 25.0 (* dy dy))))]
              (scene/rect! (- (+ x 16.0) inner) (+ y 7.0 (ak/as :f32 (ak/floatFromInt row)))
                           (* inner 2.0) 1.0 (if (ak/! enabled) 0x252b30 (if hover 0x586259 0x3d4944)) 0.0))))))
    (when (> w 45.0) (label! label (+ x 32.0) (+ y 5.0) (- w 37.0) color))
    (when hover (hint! (if enabled label (if (busy?) "Traitement en cours" "Aucune prise pour ce passage")))
      (when enabled (glfw/glfwSetCursor studio-window hand-cursor)))
    (and enabled hover clicked)))

(az/defn paragraph!
  "Wrap the complete text, clipping whole lines to this pane; never truncate a passage."
  :- :f32 [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32]
           [scale :f32] [top :f32] [bottom :f32] [color :u32]]
  (let [^{:var :usize} start 0 ^{:var :f32} cursor x ^{:var :f32} row y
        line-height (* 65.0 scale)]
    (ak/while (< start (az/field text len))
      (let [^{:var :usize} end start]
        (ak/while (and (< end (az/field text len)) (ak/!= (az/index text end) 32)
                       (ak/!= (az/index text end) 10)) (set! end (+ end 1)))
        (let [word (az/slice text start end) width-word (scene/text-width word scale)]
          (when (and (> cursor x) (> (+ cursor width-word) (+ x width)))
            (set! row (+ row line-height)) (set! cursor x))
          (when (and (>= row top) (< (+ row line-height) bottom))
            (scene/text! word cursor row scale color))
          (set! cursor (+ cursor width-word (scene/text-width " " scale))))
        (when (and (< end (az/field text len)) (ak/== (az/index text end) 10))
          (set! row (+ row line-height)) (set! cursor x))
        (set! start (+ end 1))))
    (+ row line-height)))

(az/defn selected-id :- [:slice-const :u8] []
  (let [node (ak/& (az/index (az/field (az/index scene/stories scene/active-story) nodes) selected))]
    (az/slice (az/field node id) 0 (az/field node id_len))))

(az/defn note-take! :- :bool [[id [:slice-const :u8]]]
  (when (or (ak/== (az/field id len) 0) (> (az/field id len) 64)) (ak/return false))
  (let [^{:var [:array 65 :u8]} name (mem/zeroes (az/type [:array 65 :u8]))]
    (ak/memcpy (az/slice name 0 (az/field id len)) id)
    (let [entity (flecs/ecs_lookup scene/world (ak/& name))]
      (when (ak/== entity 0) (ak/return false))
      (let [raw (flecs/ecs_get_mut_id scene/world entity scene/passage-component)]
        (when (ak/== raw ak/null) (ak/return false))
        (let [state (az/cast raw [:* scene/PassageState])]
          (set! (az/field state takes) (+ (az/field state takes) 1)) true)))))

(az/defn take-action! :- :u32 [] (ak/atomicRmw :u32 (ak/& pending) :.Xchg 0 :.acq_rel))
(az/defn request! :- :void [[action :u32]]
  (when (ak/== (ak/atomicLoad :u8 (ak/& busy) :.acquire) 0)
    (ak/atomicStore :u8 (ak/& busy) 1 :.release)
    (ak/atomicStore :u32 (ak/& pending) action :.release)))

(az/defn draw! {:attrs #{:export}} :- :void []
  (set! rendered-frames (+ rendered-frames 1))
  ;; The audio callback advances independently; sample once, not once per row.
  (set! frame-cursor (cursor-seconds))
  (set! capture-cursor (if (ak/== capture-phase 2)
    (/ (ak/as :f32 (ak/floatFromInt (recorder/frames-recorded))) 48000.0) 0.0))
  (glfw/glfwGetCursorPos studio-window (ak/& mouse-x) (ak/& mouse-y))
  (set! clicked event-pressed) (set! double-clicked event-double)
  (when event-pressed (set! mouse-x press-x) (set! mouse-y press-y))
  (set! event-pressed false) (set! event-double false)
  (when test-click
    (set! mouse-x test-x) (set! mouse-y test-y) (set! clicked true) (set! test-click false))
  (set! route-click (and (> route-menu 0) clicked))
  (when (> route-menu 0) (set! clicked false))
  (set! hint-length 0)
  (glfw/glfwSetCursor studio-window ak/null)
  (when (and clicked (ak/! (inside? 28.0 621.0 192.0 28.0))) (set! name-focus false))
  (when (>= selected scene/passage-entity-count) (set! selected 0) (set! focus-scroll 0.0))
  (set! track-offset (ak/min track-offset (- (ak/max 1 scene/passage-entity-count) 1)))
  (scene/rect! 0.0 0.0 1100.0 760.0 0x191c20 0.0)
  (scene/rect! 0.0 0.0 1100.0 94.0 0x272b30 0.0)
  (scene/text! "LA PROFESSEURE / STUDIO" 16.0 9.0 0.38 0xece3ce)
  (if mix-mode
    (do
      (when (button! (if (az/field (mixer/loop-state) enabled) "Boucle : oui" "Boucle : non") 602.0 9.0 94.0) (request! 31))
      (when (button! "A" 702.0 9.0 34.0) (request! 32))
      (when (button! "B" 742.0 9.0 34.0) (request! 33))
      (when (inside? 602.0 9.0 94.0 28.0) (hint! "Répéter le mix entre A et B. F1 : fenêtre du jeu."))
      (when (inside? 702.0 9.0 34.0 28.0) (hint! "A : placer le début de boucle au curseur."))
      (when (inside? 742.0 9.0 34.0 28.0) (hint! "B : placer la fin de boucle au curseur.")))
    (do
      (when (button! (if audition-boost "Écoute amplifiée" "Gain original") 602.0 9.0 174.0)
        (when (ak/! (busy?)) (set! audition-boost (ak/! audition-boost)) (update-audition-gain!)))
      (when (inside? 602.0 9.0 174.0 28.0)
        (hint! "Amplifier les prises faibles pour l'écoute seulement. Aucun fichier ni effet modifié. F1 : jeu."))))
  (when (button! (if mix-mode "Mode prise" "Lire mix") 420.0 9.0 164.0) (request! 30))
  (when (button! "Annuler" 788.0 9.0 140.0) (request! 27))
  (when (button! "Rétablir" 940.0 9.0 140.0) (request! 28))
  (when (icon-button! (if (playing-preview?) 1 0) (if (playing-preview?) "PAUSE" (if preview-paused "REPRENDRE" "LECTURE"))
                      16.0 51.0 130.0 (and (ak/! (busy?)) (or (ak/== record-enabled 1) mix-mode (> take-seconds 0.0) (playing-preview?))) (or (playing-preview?) (ak/== capture-phase 2)))
    (request! 6))
  (when (icon-button! 2 "STOP" 154.0 51.0 86.0 true false) (ak/atomicStore :u32 (ak/& pending) 2 :.release))
  (when (icon-button! 3 "Revenir au début (Home)" 248.0 51.0 42.0 (ak/! (busy?)) false) (position! 0.0))
  (when (icon-button! 4 "REC" 301.0 51.0 100.0 (ak/! (busy?)) (ak/== record-enabled 1)) (request! 34))
  (when (inside? 301.0 51.0 100.0 28.0) (hint! "Activer REC, puis Lecture. Armer la piste avec son cercle rouge."))
  (when (button! (if (ak/== record-mode 8) "FX" "Sec") 411.0 51.0 87.0)
    (when (ak/! (busy?)) (set! record-mode (if (ak/== record-mode 8) 1 8))))
  (scene/text! (if (ak/== capture-phase 2) "REC" (if (ak/== capture-phase 1) "DÉCOMPTE"
                    (if (busy?) "ATTENTE" (if (ak/== record-enabled 1) "REC PRÊT" (if (playing-preview?) "LECTURE" (if preview-paused "PAUSE" "POSITION")))))) 506.0 44.0 0.20 0xc9a16e)
  (let [seconds (if (ak/== capture-phase 2) capture-cursor
                   (if (ak/== capture-phase 1) (ak/as :f32 (ak/floatCast (ak/max 0.0 (- countdown-until (glfw/glfwGetTime)))))
                     (if (or mix-mode (and voice-ready (ak/== preview-node selected))) frame-cursor seek-seconds)))]
    (seconds! seconds 506.0 65.0 0.30)
    (scene/text! "s" 562.0 67.0 0.23 0xaeb6ae))
  (when (button! (if (ak/== countdown-seconds 0) "Décompte 0" "Décompte 3") 602.0 52.0 112.0)
    (when (ak/! (busy?)) (set! countdown-seconds (if (ak/== countdown-seconds 0) 3 0))))
  (when (button! (if (ak/== tail-seconds 0) "Fin 0 s" (if (ak/== tail-seconds 1) "Fin 1 s" (if (ak/== tail-seconds 3) "Fin 3 s" "Fin 5 s"))) 724.0 52.0 78.0)
    (when (ak/! (busy?)) (set! tail-seconds (if (ak/== tail-seconds 0) 1 (if (ak/== tail-seconds 1) 3 (if (ak/== tail-seconds 3) 5 0))))))
  (when (button! (if (ak/== compensate 1) "Align. oui" "Align. non") 812.0 52.0 96.0)
    (when (ak/! (busy?)) (set! compensate (- 1 compensate))))
  (when (button! "Publier au jeu" 917.0 52.0 168.0) (request! 7))
  (when (button! "Pistes" 16.0 104.0 76.0) (set! page 0))
  (when (button! "Script" 100.0 104.0 76.0) (set! page 1))
  (when (button! "<" 184.0 104.0 36.0)
    (set! track-offset (- track-offset (ak/min track-offset 6)))
    (set! scroll (ak/max 0.0 (- scroll 180.0))))
  (when (button! ">" 224.0 104.0 36.0)
    (set! track-offset (ak/min (+ track-offset 6) (- (ak/max 1 scene/passage-entity-count) 1)))
    (set! scroll (ak/min (ak/max 0.0 (- content-height 256.0)) (+ scroll 180.0))))
  (scene/text! "Prises / secondes" 284.0 108.0 0.24 0xaeb6ae)
  (when (button! (if follow-playhead "Suivre : oui" "Suivre : non") 474.0 104.0 134.0) (set! follow-playhead (ak/! follow-playhead)))
  (when (button! "-" 620.0 104.0 36.0) (zoom-at! 2.0 563.0))
  (when (button! "+" 662.0 104.0 36.0) (zoom-at! 0.5 563.0))
  (when (button! "Tout" 704.0 104.0 58.0) (set! timeline-seconds 60.0) (set! timeline-start 0.0))
  (when (button! "<" 790.0 104.0 36.0) (pan! (- (/ timeline-seconds 2.0))))
  (when (button! ">" 832.0 104.0 36.0) (pan! (/ timeline-seconds 2.0)))
  (when (and follow-playhead (playing-preview?) (or (< frame-cursor timeline-start) (> frame-cursor (+ timeline-start (* 0.94 timeline-seconds)))))
    (set! timeline-start (ak/max 0.0 (ak/min (- 60.0 timeline-seconds) (- frame-cursor (* 0.1 timeline-seconds))))))
  (scene/rect! 16.0 143.0 868.0 291.0 0x111417 0.0)
  (when (and (ak/== page 0) (inside? 242.0 140.0 636.0 27.0))
    (hint! "Règle : cliquer / glisser pour positionner")
    (glfw/glfwSetCursor studio-window resize-cursor)
    (when (and clicked (ak/! (busy?)))
      (set! trim-drag 3) (position! (time-at (ak/floatCast mouse-x))) (set! clicked false)))
  (let [data (ak/& (az/index scene/stories scene/active-story))]
    (when (ak/== page 0)
      (when (and mix-mode (> loop-to timeline-start) (< loop-from (+ timeline-start timeline-seconds)))
        (let [x (+ 242.0 (* 642.0 (ak/max 0.0 (/ (- loop-from timeline-start) timeline-seconds))))
              end-x (+ 242.0 (* 642.0 (ak/min 1.0 (/ (- loop-to timeline-start) timeline-seconds))))]
          (scene/rect! x 167.0 (- end-x x) 267.0
                       (if (az/field (mixer/loop-state) enabled) 0x253d3b 0x252a2b) 0.0)
          (scene/rect! x 163.0 (- end-x x) 3.0 0xd4b07c 0.0)))
      (dotimes [tick 7]
        (let [x (+ 242.0 (* (ak/as :f32 (ak/floatFromInt tick)) 107.0))]
          (seconds! (+ timeline-start (* (/ timeline-seconds 6.0) (ak/as :f32 (ak/floatFromInt tick)))) (- x 10.0) 143.0 0.20)
          (scene/rect! x 167.0 1.0 267.0 0x353a40 0.0)))
      (dotimes [slot 6]
        (let [i (+ track-offset (ak/as :u32 (ak/intCast slot))) y (+ 169.0 (* 44.0 (ak/as :f32 (ak/floatFromInt slot))))]
          (when (< i (az/field data count))
            (let [node (az/index (az/field data nodes) i)
                  active (ak/== selected i) capturing (and (ak/== capture-phase 2) (ak/== record-track i))
                  seconds (if capturing capture-cursor (if (ak/== track-snapshot track-offset) (az/index clip-seconds slot) 0.0))
                  start (if (and mix-mode (ak/! capturing)) (az/index clip-start slot) 0.0)]
              (scene/rect! 16.0 y 220.0 42.0 (if active 0x4f4540 0x2b3035) 0.0)
              (scene/rect! 16.0 y 4.0 42.0 (if (> (az/field node id_len) 0) 0xc78e55 0x6d7781) 0.0)
              (number! (+ i 1) 27.0 (+ y 3.0) 0.24)
              (label! (if (ak/== (az/field node speaker) 86) "LA VOITURE" (if (ak/== (az/field node speaker) 77) "LA MANGUE" "CONTEXTE"))
                      48.0 (+ y 2.0) 113.0 0xece3ce)
              (label! (scene/story-text i) 27.0 (+ y 23.0) 198.0 0xaeb6ae)
              (when (> (az/field node id_len) 0)
                (when (icon-button! 4 "Armer / désarmer cette piste" 160.0 (+ y 1.0) 30.0
                                   (ak/! (busy?)) (ak/== record-track i))
                  (set! record-track (if (ak/== record-track i) 4294967295 i))
                  (set! selected i) (set! clicked false)))
              (when (icon-button! (if (and (ak/! mix-mode) (playing-preview?) (ak/== preview-node i)) 1 0)
                        "Lire / pause cette piste" 194.0 (+ y 1.0) 34.0
                        (and (> seconds 0.0) (ak/! (busy?))) (and (playing-preview?) (ak/== preview-node i)))
                (set! selected i) (set! focus-scroll 0.0)
                (when (ak/!= preview-node i) (set! seek-seconds 0.0))
                (request! (if (and (ak/! mix-mode) (ak/== preview-node i) (or (playing-preview?) preview-paused)) 6 26))
                (set! clicked false))
              (when (and clicked (ak/! (busy?)) (>= mouse-y y) (< mouse-y (+ y 42.0)) (>= mouse-x 16.0) (< mouse-x 878.0))
                (when (ak/!= selected i) (set! seek-seconds 0.0))
                (set! selected i) (set! focus-scroll 0.0) (set! name-focus false)
                (when (and (>= mouse-x 242.0) (> seconds 0.0) (>= (time-at (ak/floatCast mouse-x)) start)
                           (< (time-at (ak/floatCast mouse-x)) (+ start seconds)))
                  (set! take-seconds seconds) (position! (time-at (ak/floatCast mouse-x)))
                  (if double-clicked (request! 26) (set! trim-drag 3))))
              (when (and (inside? 242.0 y 636.0 42.0) (> seconds 0.0))
                (hint! "Clic : position. Double-clic : lecture. Option + molette : zoom."))
              (when (and (> (+ start seconds) timeline-start) (< start (+ timeline-start timeline-seconds)))
                (let [a (ak/max 0.0 (/ (- start timeline-start) timeline-seconds))
                      end-ratio (ak/min 1.0 (/ (- (+ start seconds) timeline-start) timeline-seconds))
                      w (* 642.0 (- end-ratio a))]
                  (scene/rect! (+ 242.0 (* 642.0 a)) (+ y 2.0) w 38.0 (if capturing 0x753b3b (if active 0x826a47 0x414f54)) 0.0)
                  (let [^{:var :f32} peak 0.00001]
                    (when capturing
                      (dotimes [b 128] (set! (az/index clip-wave (+ (* slot 128) b))
                        (recorder/wave-bin (ak/== record-mode 8) (ak/intCast b)))))
                    (dotimes [b 128] (set! peak (ak/max peak (az/index clip-wave (+ (* slot 128) b)))))
                    (dotimes [b 128]
                      (let [t (+ start (* seconds (/ (ak/as :f32 (ak/floatFromInt b)) 128.0)))
                            x (+ 242.0 (* 642.0 (/ (- t timeline-start) timeline-seconds)))
                            h (* 30.0 (/ (az/index clip-wave (+ (* slot 128) b)) peak))]
                        (when (and (>= x 242.0) (< x 882.0))
                          (scene/rect! x (+ y 21.0 (- (/ h 2.0))) 2.0 (ak/max 1.0 h) 0xdcccaa 0.0)))))))
              (when (ak/== seconds 0.0)
                (scene/text! (if (> (az/field node id_len) 0) "À enregistrer" "Texte / sans voix") 252.0 (+ y 11.0) 0.23 0x6f797e))
              (when (and (ak/! mix-mode) (or (ak/== selected i) (and voice-ready (ak/== preview-node i))))
                (let [position (if capturing capture-cursor (if (and voice-ready (ak/== preview-node i)) frame-cursor seek-seconds))
                      x (playhead-x position)]
                  (when (and (>= x 242.0) (<= x 884.0)) (scene/rect! x y 2.0 42.0 0xf2b967 0.0)))))))))
    (when (ak/== page 1)
      (let [^{:var :f32} row (- 151.0 scroll)]
        (dotimes [i (az/field data count)]
          (let [top row node (az/index (az/field data nodes) i)
                indent (* 4.0 (ak/as :f32 (ak/floatFromInt (ak/min 12 (az/field node indent)))))]
            (set! row (paragraph! (scene/story-text (ak/intCast i)) (+ 28.0 indent) row (- 834.0 indent) 0.30 150.0 430.0
                        (if (ak/== i selected) 0xeac994 0xd7d2c5)))
            (when (and clicked (ak/! (busy?)) (>= mouse-x 16.0) (< mouse-x 884.0)
                       (>= mouse-y (ak/max top 150.0)) (< mouse-y (ak/min row 430.0)))
              (set! selected (ak/intCast i)) (set! focus-scroll 0.0) (set! name-focus false))
            (set! row (+ row 12.0))))
        (set! content-height (- (+ row scroll) 151.0)))))
  (when (ak/== page 0)
    (when mix-mode
      (let [x (playhead-x frame-cursor)]
        (when (and (>= x 242.0) (<= x 884.0))
          (scene/rect! x 167.0 2.0 265.0 0xf2b967 0.0))))
    (let [w (* 642.0 (/ timeline-seconds 60.0)) x (+ 242.0 (* 642.0 (/ timeline-start 60.0)))]
      (scene/rect! 242.0 432.0 642.0 8.0 0x30383e 0.0)
      (scene/rect! x 432.0 w 8.0 0x9c855f 0.0)
      (when (inside? 242.0 432.0 642.0 10.0)
        (hint! "Barre de temps : glisser pour défiler") (glfw/glfwSetCursor studio-window hand-cursor)
        (when clicked
          (set! bar-grab (if (and (>= mouse-x x) (< mouse-x (+ x w))) (- (ak/as :f32 (ak/floatCast mouse-x)) x) (/ w 2.0)))
          (set! trim-drag 5))))
    (let [total (ak/as :f32 (ak/floatFromInt (ak/max 6 scene/passage-entity-count)))
          h (ak/max 18.0 (* 262.0 (/ 6.0 total)))
          y (+ 169.0 (* (- 262.0 h) (/ (ak/as :f32 (ak/floatFromInt track-offset)) (ak/max 1.0 (- total 6.0)))))]
      (scene/rect! 880.0 169.0 12.0 262.0 0x30383e 0.0)
      (scene/rect! 882.0 y 8.0 h 0x9c855f 0.0)
      (when (inside? 879.0 169.0 14.0 262.0)
        (hint! "Pistes : glisser ou défiler à deux doigts") (glfw/glfwSetCursor studio-window hand-cursor)
        (when clicked
          (set! bar-grab (if (and (>= mouse-y y) (< mouse-y (+ y h)))
                          (- (ak/as :f32 (ak/floatCast mouse-y)) y) (/ h 2.0)))
          (set! trim-drag 6)))))
  ;; The editor is always visible: reading a long passage never hides transport or routing.
  (scene/rect! 16.0 444.0 868.0 276.0 0x22272b 0.0)
  (scene/text! "PRISE / ÉDITION" 28.0 455.0 0.28 0xc9a16e)
  (when (button! "Préc." 28.0 495.0 88.0) (request! 4))
  (when (button! "Suiv." 126.0 495.0 94.0) (request! 5))
  (when (button! "Marquer A" 28.0 535.0 192.0) (request! 10))
  (when (button! "Écouter A/B" 28.0 575.0 192.0) (request! 11))
  (when (button! (if (ak/== name-length 0) "Nom de prise / profil" (entered-name)) 28.0 621.0 192.0)
    (set! name-focus true))
  (when (inside? 28.0 621.0 192.0 28.0)
    (glfw/glfwSetCursor studio-window text-cursor)
    (hint! "Nom : saisir le texte, Entrée pour enregistrer, Échap pour quitter"))
  (when name-focus (scene/rect! 28.0 648.0 192.0 2.0 0xf2b967 0.0))
  (when (button! "Nommer" 28.0 661.0 90.0) (request! 9))
  (when (button! "Préférée" 126.0 661.0 94.0) (request! 13))
  (label! (selected-id) 242.0 452.0 444.0 0xc9a16e)
  (when (button! "Haut" 724.0 447.0 70.0) (set! focus-scroll (ak/max 0.0 (- focus-scroll 80.0))))
  (when (button! "Suite" 802.0 447.0 70.0) (set! focus-scroll (ak/min (ak/max 0.0 (- focus-height 96.0)) (+ focus-scroll 80.0))))
  (set! focus-height (- (+ focus-scroll (paragraph! (scene/story-text selected) 242.0 (- 489.0 focus-scroll)
                           625.0 0.34 489.0 594.0 0xece3ce)) 489.0))
  (waveform! 611.0)
  (when (and clicked (ak/! (busy?)) (>= mouse-x 238.0) (<= mouse-x 888.0) (>= mouse-y 607.0) (< mouse-y 679.0))
    (let [x (ak/as :f32 (ak/floatCast mouse-x))
          a (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-in))))
          b (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-out))))]
      (if (< (ak/min (ak/abs (- x a)) (ak/abs (- x b))) 9.0)
        (do (set! trim-drag (if (< (ak/abs (- x a)) (ak/abs (- x b))) 1 2)) (trim-at! x))
        (do (set! trim-drag 4) (position! (* take-seconds (ak/max 0.0 (ak/min 1.0 (/ (- x 242.0) 642.0)))))))))
  (when (inside? 238.0 607.0 650.0 72.0)
    (let [x (ak/as :f32 (ak/floatCast mouse-x))
          a (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-in))))
          b (+ 242.0 (* 6.42 (ak/as :f32 (ak/floatFromInt trim-out))))]
      (if (< (ak/min (ak/abs (- x a)) (ak/abs (- x b))) 9.0)
        (do (hint! "Bord : rogner sans modifier le fichier") (glfw/glfwSetCursor studio-window resize-cursor))
        (hint! "Onde : cliquer / glisser pour positionner"))))
  (when (and (> trim-drag 0) (or mouse-down clicked) (ak/! (busy?)))
    (cond
      (<= trim-drag 2) (trim-at! (ak/floatCast mouse-x))
      (ak/== trim-drag 3) (position! (time-at (ak/floatCast mouse-x)))
      (ak/== trim-drag 4) (position! (* take-seconds (ak/max 0.0 (ak/min 1.0 (/ (- (ak/as :f32 (ak/floatCast mouse-x)) 242.0) 642.0)))))
      (ak/== trim-drag 5) (do (set! timeline-start 0.0) (pan! (* 60.0 (/ (- (ak/as :f32 (ak/floatCast mouse-x)) 242.0 bar-grab) 642.0))))
      (ak/== trim-drag 6) (set! track-offset (track-offset-at (ak/floatCast mouse-y) scene/passage-entity-count))))
  (when (ak/! mouse-down) (set! trim-drag 0))
  (scene/text! "Glisser les bords : coupe" 242.0 690.0 0.22 0xaeb6ae)
  (number! trim-in 452.0 690.0 0.22)
  (scene/text! "%" 481.0 690.0 0.22 0xaeb6ae)
  (number! trim-out 518.0 690.0 0.22)
  (scene/text! "%" 552.0 690.0 0.22 0xaeb6ae)
  (when (button! "Réinitialiser" 594.0 685.0 122.0) (set! trim-in 0) (set! trim-out 100))
  (when (button! "Rogner copie" 726.0 685.0 146.0) (request! 12))
  ;; Explicit external routing. No dummy device or effect controls.
  (scene/rect! 896.0 104.0 190.0 616.0 0x272d32 0.0)
  (scene/text! "ROUTAGE / FX" 908.0 111.0 0.26 0xc9a16e)
  (when (button! "Micro / entrée..." 906.0 148.0 170.0)
    (when (ak/! (busy?)) (set! route-menu 1) (set! route-offset 0) (set! clicked false)))
  (label! (recorder/device-name true microphone) 908.0 180.0 166.0 0xece3ce)
  (scene/text! "Entrée" 908.0 207.0 0.22 0xaeb6ae)
  (scene/rect! 972.0 214.0 104.0 8.0 0x121619 0.0)
  (scene/rect! 972.0 214.0 (* 0.104 (ak/as :f32 (ak/floatFromInt (ak/atomicLoad :u32 (ak/& recorder/input-level) :.acquire)))) 8.0 0xc9a16e 0.0)
  (when (button! "Envoi 1/2 > Bitwig..." 906.0 234.0 170.0)
    (when (ak/! (busy?)) (set! route-menu 2) (set! route-offset 0) (set! clicked false)))
  (label! (recorder/device-name false effects-output) 908.0 266.0 166.0 0xaeb6ae)
  (when (button! "Retour FX 3/4..." 906.0 289.0 170.0)
    (when (ak/! (busy?)) (set! route-menu 3) (set! route-offset 0) (set! clicked false)))
  (label! (recorder/device-name true return-input) 908.0 316.0 166.0 0xaeb6ae)
  (scene/rect! 908.0 346.0 168.0 8.0 0x121619 0.0)
  (scene/rect! 908.0 346.0 (* 0.168 (ak/as :f32 (ak/floatFromInt (recorder/level)))) 8.0 0xc9a16e 0.0)
  (when (ak/!= (ak/atomicLoad :u8 (ak/& recorder/clipped) :.acquire) 0) (scene/text! "SATURATION" 908.0 358.0 0.23 0xff8d75))
  (when (button! "Sortie d'écoute..." 906.0 386.0 170.0)
    (when (ak/! (busy?)) (set! route-menu 4) (set! route-offset 0) (set! clicked false)))
  (label! (recorder/device-name false headphones) 908.0 419.0 166.0 0xaeb6ae)
  (scene/rect! 908.0 440.0 168.0 5.0 0x121619 0.0)
  (scene/rect! 908.0 440.0 (* 168.0 (ak/min 1.0 (* 0.00001 (ak/as :f32 (ak/floatFromInt (ak/atomicLoad :u32 (ak/& playback-level) :.acquire)))))) 5.0 0xc9a16e 0.0)
  (when (button! (if (ak/== monitor-enabled 0) "Retour direct : non" "Retour direct : oui") 906.0 450.0 170.0) (request! 23))
  (when (inside? 906.0 450.0 170.0 28.0) (hint! "Retour micro pendant la prise : casque requis. Ne coupe pas la lecture des prises."))
  (when (button! "Vol -" 906.0 488.0 78.0)
    (let [v (ak/atomicLoad :u32 (ak/& recorder/monitor-gain) :.acquire)]
      (ak/atomicStore :u32 (ak/& recorder/monitor-gain) (- v (ak/min v 5)) :.release)))
  (when (button! "Vol +" 994.0 488.0 82.0)
    (ak/atomicStore :u32 (ak/& recorder/monitor-gain) (ak/min 50 (+ 5 (ak/atomicLoad :u32 (ak/& recorder/monitor-gain) :.acquire))) :.release))
  (when (button! "Sauver profil" 906.0 528.0 170.0) (request! 20))
  (when (button! "Profil suivant" 906.0 566.0 170.0) (request! 21))
  (when (button! "Reconnecter" 906.0 604.0 170.0) (request! 22))
  (when (button! "Passe FX" 906.0 642.0 170.0) (request! 3))
  (when (button! "Récupérer" 906.0 680.0 170.0) (request! 14))
  (label! (az/slice details 0 details-length) 16.0 729.0 520.0 0xc9a16e)
  (label! (if (> hint-length 0) (az/slice hint-text 0 hint-length) (az/slice status-text 0 status-length)) 550.0 729.0 535.0 0xd7d2c5)
  (when (> route-menu 0)
    (set! clicked route-click)
    (let [capture (or (ak/== route-menu 1) (ak/== route-menu 3))
          total (if capture recorder/capture-count recorder/playback-count)]
      (scene/rect! 558.0 137.0 522.0 371.0 0x111719 0.0)
      (scene/text! (if (ak/== route-menu 1) "CHOISIR LE MICRO / ENTRÉE"
                    (if (ak/== route-menu 2) "ENVOI VERS BITWIG (1/2)"
                      (if (ak/== route-menu 3) "RETOUR TRAITÉ (3/4)" "SORTIE : PRISES / MIX / CASQUE"))) 574.0 146.0 0.30 0xece3ce)
      (when (button! "X" 1030.0 144.0 34.0) (set! route-menu 0))
      (dotimes [row 8]
        (let [i (+ route-offset (ak/as :u32 (ak/intCast row)))]
          (when (< i total)
            (when (button! (recorder/device-name capture i) 574.0 (+ 187.0 (* 34.0 (ak/as :f32 (ak/floatFromInt row)))) 490.0)
              (when (ak/! (busy?))
                (cond (ak/== route-menu 1) (set! microphone i)
                      (ak/== route-menu 2) (set! effects-output i)
                      (ak/== route-menu 3) (set! return-input i)
                      (ak/== route-menu 4) (do (close-playback!) (mixer/close!)
                                              (set! preview-paused false) (set! headphones i)))
                (set! route-menu 0))))))
      (when (button! "Précédents" 574.0 467.0 130.0) (set! route-offset (- route-offset (ak/min route-offset 8))))
      (when (button! "Suivants" 716.0 467.0 130.0)
        (when (< (+ route-offset 8) total) (set! route-offset (+ route-offset 8)))))))

(az/defn build-frame {:attrs #{:export}} :- :u32
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (set! framebuffer-scale (/ (ak/as :f32 (ak/floatFromInt width)) 1100.0))
  (set! _ height)
  (let [old-vertices scene/vertices old-count scene/vertex-count]
    (ak/defer (do (set! scene/vertices old-vertices) (set! scene/vertex-count old-count)))
    (set! scene/vertices output) (set! scene/vertex-count 0)
    (draw!) scene/vertex-count))

(az/defn focus-window! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when (ak/!= studio-window ak/null)
    (glfw/glfwShowWindow studio-window) (glfw/glfwFocusWindow studio-window)))

(az/defn tick-window! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when (or (ak/! attached) (ak/== studio-window ak/null)) (ak/return))
  ;; Window close hides without discarding a recording. F1 shows it again;
  ;; close! explicitly releases the tool's resources, never the game's.
  (when (ak/!= (glfw/glfwWindowShouldClose studio-window) 0)
    (ak/atomicStore :u32 (ak/& pending) 2 :.release)
    (glfw/glfwSetWindowShouldClose studio-window 0) (glfw/glfwHideWindow studio-window))
  ;; Keep game audio out of a take even when switching to Bitwig during capture.
  (suppress-game-audio! (or (busy?) (ak/!= capture-phase 0)
    (and (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
         (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)
         (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_FOCUSED) 0))))
  (when (or (ak/== (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_VISIBLE) 0)
            (ak/!= (glfw/glfwGetWindowAttrib studio-window glfw/GLFW_ICONIFIED) 0)) (ak/return))
  (gpu/swap-context! (ak/& renderer))
  (ak/defer (gpu/swap-context! (ak/& renderer)))
  ;; The existing game watcher publishes first. Refresh this window once for
  ;; that revision, retaining its old pipeline on failure (no per-frame retries).
  (when (ak/!= observed-shaders gpu/shader-publications)
    (when (ak/! (gpu/reload-shaders!)) (status! "Shader studio refusé; version précédente conservée."))
    (set! observed-shaders gpu/shader-publications))
  (set! _ (gpu/render! (ak/& build-frame))))

(az/defn reload-assets! {:zig/qualifiers "callconv(.c)"} :- :void []
  (when attached
    (gpu/swap-context! (ak/& renderer))
    (ak/defer (gpu/swap-context! (ak/& renderer)))
    (gpu/renderer-wait-idle!) (gpu/load-atlas!)))

(az/defn attach! :- :void []
  (when (ak/== studio-window ak/null)
    (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
    (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
    (set! studio-window (glfw/glfwCreateWindow 1100 760 "La Professeure | Studio" ak/null ak/null))
    (when (ak/== studio-window ak/null) (ak/return))
    (gpu/swap-context! (ak/& renderer))
    (set! _ (gpu/initialize-renderer! studio-window))
    (set! gpu/lighting 0.0)
    (gpu/swap-context! (ak/& renderer))
    (set! observed-shaders gpu/shader-publications))
  (set! attached true)
  (set! scene/development-tick (ak/& tick-window!))
  (set! scene/development-shutdown (ak/& detach!))
  (set! scene/development-assets (ak/& reload-assets!))
  (set! scene/development-focus (ak/& focus-window!))
  (glfw/glfwShowWindow studio-window)
  (glfw/glfwFocusWindow studio-window)
  (when (ak/! callbacks-installed)
    (set! previous-char (glfw/glfwSetCharCallback studio-window (ak/& typed!)))
    (set! previous-scroll (glfw/glfwSetScrollCallback studio-window (ak/& scrolled!)))
    (set! previous-key (glfw/glfwSetKeyCallback studio-window (ak/& key-event!)))
    (set! previous-mouse (glfw/glfwSetMouseButtonCallback studio-window (ak/& mouse-event!)))
    (set! hand-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HAND_CURSOR))
    (set! resize-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_HRESIZE_CURSOR))
    (set! text-cursor (glfw/glfwCreateStandardCursor glfw/GLFW_IBEAM_CURSOR))
    (set! callbacks-installed true))
  (set! event-pressed false) (set! mouse-down false) (set! trim-drag 0)
  (status! "Choisir le passage puis le microphone. Les prises restent locales."))

(az/defn detach! {:zig/qualifiers "callconv(.c)"} :- :void []
  (close-playback!) (mixer/close!)
  (suppress-game-audio! false)
  (when callbacks-installed
    (set! _ (glfw/glfwSetCharCallback studio-window previous-char))
    (set! _ (glfw/glfwSetScrollCallback studio-window previous-scroll))
    (set! _ (glfw/glfwSetKeyCallback studio-window previous-key))
    (set! _ (glfw/glfwSetMouseButtonCallback studio-window previous-mouse))
    (glfw/glfwSetCursor studio-window ak/null)
    (glfw/glfwDestroyCursor hand-cursor) (glfw/glfwDestroyCursor resize-cursor) (glfw/glfwDestroyCursor text-cursor)
    (set! hand-cursor ak/null) (set! resize-cursor ak/null) (set! text-cursor ak/null)
    (set! callbacks-installed false))
  (when (ak/!= studio-window ak/null)
    (gpu/swap-context! (ak/& renderer))
    (gpu/shutdown-renderer!)
    (gpu/swap-context! (ak/& renderer))
    (glfw/glfwDestroyWindow studio-window) (set! studio-window ak/null))
  (set! scene/development-tick ak/null) (set! scene/development-shutdown ak/null)
  (set! scene/development-assets ak/null)
  (set! scene/development-focus ak/null) (set! attached false))

(defonce worker (atom nil))
(defonce session (atom nil))
(defonce takes (atom {}))
(defonce project (atom nil))
(defonce mix-sources (atom []))
(defonce armed (atom nil))
(defonce comparison (atom nil))
(defonce display-cache (atom nil))
(defonce waveform-cache (atom {}))
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
(defn- remember! [id kind path]
  (change-takes! "Ajouter une prise"
    #(update % id (fn [entry] (-> (or entry {}) (assoc kind path :selected path)
                                (update :history (fnil conj []) {:kind kind :path path}))))))
(defn- native-string [value]
  (try (String. (byte-array (map unchecked-byte (az/value value))) "UTF-8")
       (finally (az/close! value))))
(defn- render! [f]
  (let [result (deref (core/on-render! f) 30000 ::timeout)]
    (when (= result ::timeout) (throw (ex-info "Render thread timeout" {})))
    (when (:error result) (throw (:error result))) (:value result)))
(defn- message! [text] (render! #(status! text)))

(declare compensate-take! checkpoint! begin-recovery! recover! refresh-display! emit-event!)

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
    (remember! id (if processed? :wet :dry) path)
    (render! #(note-take! id))
    (when-let [manifest (:manifest @session)]
      (files/atomic-edn! manifest (assoc (files/read-state manifest {}) :completed? true)))
    (reset! session nil)
    (message! (if (recorder/validate-take! path)
                (str (if dry-path "Prises sèche + traitée sauvées, " "Prise sauvée, ")
                     (format "%.2f" (/ (double (az/value recorder/measured-frames)) 48000.0)) " s. Écouter ou publier.")
                "Prise conservée mais silence / saturation / fichier invalide : publication refusée."))
    (when (and (or dry-path alignment-source) processed? (= 1 (render! #(az/value compensate))))
      (compensate-take! id (or dry-path alignment-source) path))
    (emit-event! {:type :recording/saved :id id :path path :dry-path dry-path
                  :selected (get-in @takes [id :selected])})))

(defn- focused-id [] (native-string (render! #(selected-id))))

(defn- stop-mix! []
  (mixer/close!)
  (render! #(do (az/set-value! mix-mode false) (az/set-value! preview-paused false))))

(defn- prepare-mix! []
  (stop-mix!)
  (render! #(stop-voice!))
  (let [sources (->> @takes (sort-by key)
                     (keep (fn [[id entry]] (when-let [path (:selected entry)] {:id id :path path}))) vec)]
    (when (or (empty? sources) (> (count sources) 16))
      (throw (ex-info "Mix requires 1–16 selected takes" {:code :mix-capacity})))
    (mixer/reset!) (reset! mix-sources [])
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
  (when-not (mixer/open!) (throw (ex-info "Mix output device could not start" {:code :mix-device})))
  (render! #(do (stop-voice!) (az/set-value! mix-mode true)
               (az/set-value! preview-node (az/value selected)) (az/set-value! preview-paused false)))
  (when (>= (mixer/cursor-frame) (az/value mixer/duration)) (mixer/seek! 0))
  (mixer/play!))

(defn select-take! [forward?]
  (stop-mix!)
  (let [id (focused-id) {:keys [history selected]} (get @takes id)
        paths (mapv :path history)]
    (when (empty? paths) (throw (ex-info "Aucune prise pour ce passage." {})))
    (let [index (mod (+ (.indexOf paths selected) (if forward? 1 -1)) (count paths))
          entry (nth history index)]
      (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
                    (az/set-value! preview-paused false) (az/set-value! seek-seconds 0.0)))
      (change-takes! "Choisir une prise" #(assoc-in % [id :selected] (:path entry)))
      (message! (str "Prise " (inc index) "/" (count paths) " — " (if (= :wet (:kind entry)) "traitée" "sèche")))
      entry)))

(defn preview! []
  (stop-mix!)
  (let [path (get-in @takes [(focused-id) :selected])]
    (when-not path (throw (ex-info "Choisir une prise." {})))
    (let [{:keys [frames bins]} (files/waveform path)]
      (render! #(az/set-value! audition-source-peak (apply max 0.0 bins)))
      (when-not (render! #(when (play-voice-file! path)
                            (az/set-value! take-seconds (/ frames 48000.0))
                            (az/set-value! preview-node (az/value selected)) (az/set-value! preview-paused false)
                            (seek-preview!) true))
        (throw (ex-info "Sortie audio indisponible. Choisir une sortie d'écoute, puis réessayer." {:code :playback-device}))))
    (message! (str "Écoute → " (native-string (recorder/device-name false (render! #(az/value headphones))))))))

(defn publish!
  "Atomically publish a validated processed take; game watches the stable passage path."
  []
  (let [id (focused-id) {:keys [selected history]} (get @takes id)
        entry (some #(when (= selected (:path %)) %) history)]
    (when-not (and (re-matches #"[A-Za-z0-9_-]+" id) (= :wet (:kind entry)))
      (throw (ex-info "Choisir une prise traitée avant publication." {})))
    (when-not (recorder/validate-take! selected)
      (throw (ex-info "Publication refusée : silence, saturation ou fichier invalide." {})))
    (let [destination (io/file "resources/voices" (str id ".wav"))]
      (atomic-write! destination #(io/copy (io/file selected) %))
      (change-takes! "Publier au jeu" #(assoc-in % [id :published] selected) true)
      (message! "Voix publiée. F1 : écouter le passage dans le jeu.")
      (.getCanonicalPath destination))))

(defn- start-take! [processed?]
  (let [id (native-string (render! #(selected-id)))
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Select a voiced passage with a recording ID" {:id id})))
        capture (render! #(az/value (if processed? return-input microphone)))
        playback (render! #(az/value effects-output))
        path (io/file "build/recording" id (str (java.util.UUID/randomUUID) (if processed? "-wet.wav" "-dry.wav")))]
    (when processed?
      (when-not (and (get-in @takes [id :dry]) (recorder/load-dry! (get-in @takes [id :dry])))
        (throw (ex-info "Record/load a dry take for this passage first" {:id id})))
      (doseq [name [(native-string (recorder/device-name true capture)) (native-string (recorder/device-name false playback))]]
        (when-not (= "BlackHole 16ch" name)
          (throw (ex-info "Effects pass requires explicit BlackHole 16ch input/output" {:device name})))))
    (io/make-parents path)
    ;; Reserve a fresh take path; never overwrite a previous take.
    (when-not (.createNewFile path) (throw (ex-info "Take path already exists" {:path path})))
    (when-not (recorder/start! capture playback (if processed? 2 1) (* 48000 (render! #(az/value tail-seconds))))
      (throw (ex-info "Audio device could not start (or no dry take loaded)" {})))
    (reset! session {:id id :path (.getCanonicalPath path) :processed? processed?
                     :alignment-source (when processed? (get-in @takes [id :dry]))})
    (begin-recovery!)
    (render! #(do (az/set-value! busy 1) (az/set-value! capture-phase 2)))
    (message! (if processed? "Passe temps réel : Bitwig doit renvoyer sur 3/4." "Enregistrement micro. Arrêter pour conserver la prise."))))

(defn- start-live-take! []
  (let [id (focused-id)
        _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
            (throw (ex-info "Choisir un passage avec un identifiant de voix." {})))
        capture (render! #(az/value microphone))
        return-index (render! #(az/value return-input))
        send-index (render! #(az/value effects-output))
        stem (str (java.util.UUID/randomUUID))
        dry-path (io/file "build/recording" id (str stem "-dry.wav"))
        wet-path (io/file "build/recording" id (str stem "-wet.wav"))]
    (io/make-parents dry-path)
    (doseq [path [dry-path wet-path]]
      (when-not (.createNewFile path) (throw (ex-info "Take path already exists" {:path path}))))
    (when (and (= 1 (render! #(az/value monitor-enabled)))
               (not (recorder/headphone-device? (render! #(az/value headphones)))))
      (throw (ex-info "Brancher/sélectionner un casque avant le retour direct." {})))
    (when-not (recorder/start-live! capture return-index send-index (* 48000 (render! #(az/value tail-seconds))))
      (throw (ex-info "Direct FX : impossible d'ouvrir la source et BlackHole 16ch." {})))
    (reset! session {:id id :path (.getCanonicalPath wet-path)
                     :dry-path (.getCanonicalPath dry-path) :processed? true :live? true})
    (begin-recovery!)
    (when (= 1 (render! #(az/value monitor-enabled)))
      (when-not (recorder/start-monitor! return-index (render! #(az/value headphones)))
        (throw (ex-info "Casque indisponible; source arrêtée, prise récupérable." {}))))
    (render! #(do (az/set-value! busy 1) (az/set-value! capture-phase 2)))
    (message! "DIRECT + FX : source + retour enregistrés, récupération active.")))

(defn- finish-take! []
  (stop-mix!)
  (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
               (az/set-value! seek-seconds 0.0) (az/set-value! preview-paused false)))
  (if @armed
    (do (reset! armed nil) (render! #(az/set-value! busy 0)) (message! "Décompte annulé. Aucun micro ouvert."))
    (if (:live? @session)
    (do (recorder/finish-live!)
        (message! "Fin de l'envoi. Capture de la fin des effets..."))
    (do (save-take!) (render! #(az/set-value! busy 0))))))

(defn- schedule! [action]
  (stop-mix!)
  (when-not (recorder/initialize!)
    (throw (ex-info "Entrées audio indisponibles. Reconnecter avant d'enregistrer." {})))
  (render! #(let [index (az/value record-track)]
              (when (>= index (az/value scene/passage-entity-count))
                (throw (ex-info "Armer une piste : cliquer son cercle rouge, puis Lecture." {:code :no-armed-track})))
              (az/set-value! selected index)))
  (when-not (re-matches #"[A-Za-z0-9_-]+" (focused-id))
    (throw (ex-info "Choisir un passage à enregistrer." {})))
  (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
                (az/set-value! preview-paused false) (az/set-value! seek-seconds 0.0)
                (begin-countdown-clock!)))
  (reset! armed {:action action :until (+ (System/currentTimeMillis) (* 1000 (render! #(az/value countdown-seconds))))}))

(defn- play-transport! []
  (if (= 1 (render! #(az/value record-enabled)))
    (schedule! (render! #(az/value record-mode)))
    (when-not (render! #(playing-preview?))
      (when-not (render! #(resume-preview!)) (preview!)))))

(defn- begin-recovery! []
  (let [{:keys [id live? processed?]} @session
        dir (io/file "build/recording/recovery" (str (java.util.UUID/randomUUID)))
        kinds (if live? [:dry :wet] [(if processed? :wet :dry)])
        manifest (io/file dir "session.edn")]
    (io/make-parents manifest)
    (doseq [kind kinds] (when-not (.createNewFile (io/file dir (str (name kind) ".pcm")))
                         (throw (ex-info "Recovery file exists" {}))))
    (files/atomic-edn! manifest {:id id :completed? false
                               :streams (into {} (map (fn [k] [k {:file (str (name k) ".pcm") :frames 0}]) kinds))})
    (swap! session assoc :manifest (.getCanonicalPath manifest) :checkpoint-at 0)))

(defn- checkpoint! [force?]
  (when-let [{:keys [manifest checkpoint-at]} @session]
    (when (and manifest (or force? (> (- (System/currentTimeMillis) checkpoint-at) 1000)))
      (let [state (files/read-state manifest {}) dir (.getParentFile (io/file manifest))
            updated (update state :streams
                      #(into {} (for [[kind {:keys [file frames] :as entry}] %]
                                  (let [expected (recorder/available-frames (= kind :wet))
                                        end (recorder/journal! (.getCanonicalPath (io/file dir file)) (= kind :wet) frames)]
                                    (when (< end (max frames expected)) (throw (ex-info "Recovery checkpoint failed; PCM retained" {})))
                                    [kind (assoc entry :frames end)]))))]
        (files/atomic-edn! manifest updated)
        (swap! session assoc :checkpoint-at (System/currentTimeMillis))))))

(defn recover! []
  (when (or @session @armed) (throw (ex-info "Arrêter la prise avant récupération." {})))
  (let [manifests (filter #(and (.isFile %) (= "session.edn" (.getName %)))
                         (file-seq (io/file "build/recording/recovery")))
        count (atom 0)]
    (doseq [manifest manifests :when (not (:completed? (files/read-state manifest {})))]
      (doseq [{:keys [id kind path]} (files/recover-journal! manifest)]
        (remember! id kind path) (swap! count inc))
      (files/atomic-edn! manifest (assoc (files/read-state manifest {}) :completed? true :recovered? true)))
    (message! (str @count " prise(s) récupérée(s). Original PCM conservé.")) @count))

(defn- compensate-take! [id dry-path wet-path]
  (try
    (let [result (files/alignment dry-path wet-path)]
      (if (:accepted? result)
        (let [target (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-aligned.wav"))
              end (quot (alength ^bytes (files/pcm wet-path)) 8)
              path (files/trim! wet-path target (:frames result) end)]
          (remember! id :wet path)
          (change-takes! "Aligner une prise"
            #(update-in % [id :history]
                 (fn [entries] (mapv (fn [e] (if (= (:path e) path) (assoc e :source wet-path :alignment result :name "Retour aligné") e)) entries))))
          (message! (format "Copie alignée : %.2f ms. Originaux conservés." (:milliseconds result))))
        (message! "Alignement incertain : aucun son coupé. Originaux conservés.")))
    (catch Exception e (message! (str "Prise sauvée. Alignement refusé : " (.getMessage e))))))

(defn- selected-entry []
  (let [id (focused-id) path (get-in @takes [id :selected])]
    [id (or (some #(when (= path (:path %)) %) (get-in @takes [id :history]))
            (throw (ex-info "Sélectionner une prise." {})))]))

(defn- edit-take! [operation]
  (let [[id entry] (selected-entry) path (:path entry)]
    (case operation
      :name (let [label (native-string (render! #(entered-name)))]
              (when (empty? (.trim label)) (throw (ex-info "Saisir un nom." {})))
              (change-takes! "Nommer une prise"
                #(update-in % [id :history] (fn [entries] (mapv (fn [e] (if (= path (:path e)) (assoc e :name label) e)) entries))))
              (message! "Nom sauvegardé."))
      :a (do (reset! comparison {:id id :path path :playing-a? false}) (message! "Prise A mémorisée. Choisir B puis Écouter A/B."))
      :compare (let [a @comparison]
                 (when-not (= id (:id a)) (throw (ex-info "Marquer une prise A pour ce passage." {})))
                 (let [next-a? (not (:playing-a? a)) target (if next-a? (:path a) path)
                       peak (apply max 0.0 (:bins (files/waveform target)))]
                   (render! #(az/set-value! audition-source-peak peak))
                   (when-not (render! #(when (play-voice-file! target)
                                         (az/set-value! preview-node (az/value selected))
                                         (az/set-value! preview-paused false) (az/set-value! seek-seconds 0.0) true))
                     (throw (ex-info "Lecture impossible." {})))
                   (swap! comparison assoc :playing-a? next-a?) (message! (if next-a? "Écoute A" "Écoute B"))))
      :trim (let [frames (quot (alength ^bytes (files/pcm path)) 8)
                  from (quot (* frames (render! #(az/value trim-in))) 100)
                  to (quot (* frames (render! #(az/value trim-out))) 100)
                  target (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-trim.wav"))]
              (remember! id (:kind entry) (files/trim! path target from to))
              (message! "Copie rognée créée. L'original est intact."))
      :preferred (do (change-takes! "Prise préférée" #(assoc-in % [id :preferred] path)) (message! "Prise préférée mémorisée.")))))

(defn- device-names [capture?]
  (mapv #(native-string (recorder/device-name capture? %))
        (range (az/value (if capture? recorder/capture-count recorder/playback-count)))))

(defn- routing! [operation]
  (case operation
    :save (let [name (native-string (render! #(entered-name)))
                ins (device-names true) outs (device-names false)]
            (when (empty? (.trim name)) (throw (ex-info "Nommer ce profil." {})))
            (swap! presets assoc name {:source (nth ins (render! #(az/value microphone)))
                                       :return (nth ins (render! #(az/value return-input)))
                                       :send (nth outs (render! #(az/value effects-output)))
                                       :headphones (nth outs (render! #(az/value headphones)))
                                       :tail (render! #(az/value tail-seconds))
                                       :countdown (render! #(az/value countdown-seconds))})
            (reset! active-preset name) (files/atomic-edn! presets-file @presets)
            (message! (str "Profil enregistré : " name)))
    :next (let [names (vec (sort (keys @presets)))]
            (when (empty? names) (throw (ex-info "Enregistrer un profil d'abord." {})))
            (reset! active-preset (nth names (mod (inc (.indexOf names @active-preset)) (count names))))
            (message! (str "Profil choisi : " @active-preset ". Reconnecter pour appliquer.")))
    :connect (let [preset (or (get @presets @active-preset) (throw (ex-info "Choisir un profil." {})))]
               (when-not (and (every? string? (map preset [:source :return :send :headphones]))
                              (#{0 1 3 5} (:tail preset)) (#{0 3} (:countdown preset)))
                 (throw (ex-info "Profil de routage invalide." {})))
               ;; Re-enumeration invalidates indices. Failure must not capture a different microphone.
               ;; Device names are also read by draw!: replace the context between frames.
               (stop-mix!)
               (render! #(do (close-playback!) (az/set-value! monitor-enabled 0)
                             (doseq [field [microphone return-input effects-output headphones]]
                               (az/set-value! field 4294967294))
                             (recorder/shutdown!)
                             (when-not (recorder/initialize!) (throw (ex-info "Énumération audio échouée." {})))))
               (let [ins (device-names true) outs (device-names false)
                     values [(files/resolve-device ins (:source preset)) (files/resolve-device ins (:return preset))
                             (files/resolve-device outs (:send preset)) (files/resolve-device outs (:headphones preset))]]
                 (doseq [[field value] (map vector [microphone return-input effects-output headphones] values)]
                   (render! #(az/set-value! field value)))
                 (render! #(do (az/set-value! tail-seconds (:tail preset)) (az/set-value! countdown-seconds (:countdown preset))
                               (az/set-value! monitor-enabled 0))))
               (message! "Routage reconnecté. Casque désactivé par sécurité."))
    :monitor (if (= 1 (render! #(az/value monitor-enabled)))
               (do (recorder/stop-monitor!) (render! #(az/set-value! monitor-enabled 0)))
               (let [index (render! #(az/value headphones))]
                 (when-not (recorder/headphone-device? index)
                   (throw (ex-info "Casque requis. Haut-parleurs/loopback refusés." {})))
                 (render! #(az/set-value! monitor-enabled 1))
                 (message! "Retour casque activé pour la prochaine prise FX (15 %, max 50 %).")))))

(defn- refresh-display! []
  ;; Immutable take files: decode once off the render thread, upload a bounded six-row snapshot.
  (let [[offset ids] (render! #(let [offset (az/value track-offset) count (az/value scene/passage-entity-count)]
                              [offset (mapv (fn [i] (native-string (node-id i)))
                                            (range offset (min count (+ offset 6))))]))
        rows (mapv (fn [id]
                     (let [path (get-in @takes [id :selected])
                           data (when path (or (get @waveform-cache path)
                                               (let [v (files/waveform path)] (swap! waveform-cache assoc path v) v)))]
                       {:seconds (/ (or (:frames data) 0) 48000.0)
                        :start (or (:start (some #(when (= id (:id %)) %) @mix-sources)) 0.0)
                        :bins (or (:bins data) (repeat 128 0.0))
                        :count (count (get-in @takes [id :history]))})) ids)]
    (render! #(do (doseq [[slot {:keys [seconds bins count start]}] (map-indexed vector rows)]
                    (clip! slot seconds count)
                    (clip-start! slot start)
                    (doseq [[bin value] (map-indexed vector bins)] (clip-bin! slot bin value)))
                  (az/set-value! track-snapshot offset))))
  (let [id (focused-id) path (get-in @takes [id :selected])
        entry (some #(when (= path (:path %)) %) (get-in @takes [id :history]))
        key [id path]
        recording? (boolean @session)]
    (when (and path (not recording?) (not= key @display-cache))
      (let [{:keys [bins frames]} (files/waveform path)]
        (render! #(do (az/set-value! take-seconds (/ frames 48000.0))
                      (az/set-value! trim-in 0) (az/set-value! trim-out 100)
                      (doseq [[i v] (map-indexed vector bins)] (set-wave! i v)))))
      (reset! display-cache key))
    (when (and (nil? path) (not recording?) (not= key @display-cache))
      (render! #(do (az/set-value! take-seconds 0.0) (dotimes [i 128] (set-wave! i 0.0))))
      (reset! display-cache key))
    (when recording?
      (let [processed? (:processed? @session) bins (mapv #(recorder/wave-bin processed? %) (range 128))]
        (render! #(doseq [[i v] (map-indexed vector bins)] (set-wave! i v)))))
    (let [text (cond @armed (str "Départ dans " (max 0 (long (Math/ceil (/ (- (:until @armed) (System/currentTimeMillis)) 1000.0)))) " s — Arrêter pour annuler")
                     recording? (format "REC %.1f s | sauvegarde PCM active | onde normalisée" (/ (recorder/frames-recorded) 48000.0))
                     :else (str (or (:name entry) (when entry (str "Prise " (inc (.indexOf (vec (get-in @takes [id :history])) entry)))))
                                " | " (name (or (:kind entry) :aucune))
                                " | coupe " (render! #(az/value trim-in)) "–" (render! #(az/value trim-out)) "%"
                                (when (and path (= path (get-in @takes [id :preferred]))) " | préférée")
                                (when @active-preset (str " | " @active-preset))))]
      (render! #(details! text)))))

(defn import-dry!
  "Import an existing WAV for the focused passage; the effects button records its return."
  [path]
  (when (render! #(busy?)) (throw (ex-info "Stop the current take before importing" {})))
  (render! #(az/set-value! busy 1))
  (try
    (let [id (native-string (render! #(selected-id)))
          _ (when-not (re-matches #"[A-Za-z0-9_-]+" id)
              (throw (ex-info "Select a voiced passage first" {})))
          destination (io/file "build/recording" id (str (java.util.UUID/randomUUID) "-dry.wav"))]
      (when-not (recorder/load-dry! (.getCanonicalPath (io/file path)))
        (throw (ex-info "WAV could not be decoded within the recording limit" {:path path})))
      (io/make-parents destination)
      (when-not (.createNewFile destination) (throw (ex-info "Take already exists" {})))
      (when-not (recorder/write-take! (str destination) false) (throw (ex-info "Cannot save dry take" {})))
      (remember! id :dry (.getCanonicalPath destination))
      (message! "Prise sèche importée. Effets / retour lance la capture traitée.")
      (get @takes id))
    (finally (render! #(az/set-value! busy 0)))))

(def ^:private core-commands
  {:transport/play {} :transport/pause {} :transport/toggle {} :transport/stop {}
   :mix/prepare {} :mix/play {} :mix/stop {} :mix/toggle {}
   :mix/loop {:from :nonnegative-number :to :nonnegative-number :enabled :boolean}
   :mix/clip {:id :string :start :nonnegative-number :gain :nonnegative-number :pan :finite-number
              :fade :nonnegative-number :mute :boolean :solo :boolean}
   :project/undo {} :project/redo {}
   :transport/launch {} :transport/rewind {} :transport/seek {:seconds :nonnegative-number}
   :selection/passage {:id :string} :view/zoom {:factor :positive-number} :view/pan {:seconds :finite-number}
   :take/previous {} :take/next {} :take/name {:name :string} :take/mark-a {} :take/compare {}
   :take/trim {:from :percentage :to :percentage} :take/preferred {} :take/publish {} :take/recover {}
   :record/dry {} :record/fx {} :record/process {} :record/toggle {}
   :record/enable {:enabled :boolean} :record/arm {:id :string :enabled :boolean}
   :playback/boost {:enabled :boolean}
   :routing/select {:source :device-index :send :device-index :return :device-index :headphones :device-index}
   :routing/save {} :routing/next {} :routing/connect {} :routing/monitor {}})

(defn capabilities
  "Discover the implemented API, not future roadmap features. Extensions run on the control worker."
  []
  {:api-version 1 :transport :take-or-mix :multitrack? true :arranger? false :queue-capacity 64
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
  (when-not (and (integer? cursor) (<= 0 cursor)) (throw (ex-info "Invalid event cursor" {:code :invalid-argument})))
  (let [{:keys [sequence events]} @event-log]
    {:cursor sequence :resync? (or (> cursor sequence) (< cursor (dec (or (:sequence (first events)) 1))))
     :events (filterv #(> (:sequence %) cursor) events)}))

(defn query
  "Immutable control-plane snapshot. Call from an nREPL/client thread, never an audio/render callback."
  []
  (merge {:api-version 1 :open? (boolean @worker) :event-cursor (:sequence @event-log)
          :project (when-let [p @project] {:id (:project-id p) :revision (:revision p)
                                         :undo (mapv :label (:undo p)) :redo (mapv :label (:redo p))})
          :mix {:sources @mix-sources :frames (mixer/cursor-frame) :duration (az/value mixer/duration)
                :playing? (mixer/playing?)
                :loop (let [v (mixer/loop-state)]
                        (try (let [state (az/value v)]
                               {:from-frame (:from state) :to-frame (:to state) :enabled (:enabled state)})
                             (finally (az/close! v))))}
          :recording (some-> @session (select-keys [:id :path :processed?]))
          :countdown (some-> @armed (select-keys [:until :action])) :takes @takes :routing-profile @active-preset}
         (render! #(hash-map :selection (native-string (selected-id))
                            :record-control {:enabled? (= 1 (az/value record-enabled))
                                             :armed-index (az/value record-track)
                                             :mode (if (= 8 (az/value record-mode)) :fx :dry)
                                             :phase (az/value capture-phase)
                                             :count-in-seconds (az/value countdown-seconds)}
                            :audio-focus {:game-suppressed? (game-audio-suppressed?)}
                            :playback {:output-index (az/value playback-output)
                                       :audition-gain (az/value audition-gain)
                                       :peak (/ (double (playback-peak-value)) 1000000.0)
                                       :signal-frames (playback-signal-count)}
                            :devices {:inputs (mapv (fn [i] (native-string (recorder/device-name true i))) (range (az/value recorder/capture-count)))
                                      :outputs (mapv (fn [i] (native-string (recorder/device-name false i))) (range (az/value recorder/playback-count)))
                                      :selected {:source (az/value microphone) :send (az/value effects-output)
                                                 :return (az/value return-input) :headphones (az/value headphones)}}
                            :transport {:playing? (playing-preview?) :paused? (az/value preview-paused)
                                        :seconds (if (< (az/value preview-node) 1024)
                                                   (cursor-seconds) (az/value seek-seconds))}
                            :view {:start (az/value timeline-start) :seconds (az/value timeline-seconds)
                                   :loop-selection {:from (az/value loop-from) :to (az/value loop-to)}
                                   :track-offset (az/value track-offset) :follow? (az/value follow-playhead)}))))

(defn register-command!
  "Register trusted dev tooling, not untrusted plugins. Handler receives args; it must not block indefinitely."
  [op {:keys [description validate handler] :as spec}]
  (when-not (and (qualified-keyword? op) (not (contains? core-commands op))
                 (string? description) (ifn? validate) (ifn? handler))
    (throw (ex-info "Extension needs a namespaced command, description, validator and handler" {:code :invalid-extension})))
  (swap! extensions assoc op spec) op)

(defn unregister-command! [op] (swap! extensions dissoc op) op)

(defn- valid-argument? [kind value]
  (case kind
    :device-index (and (integer? value) (<= 0 value 255))
    :string (and (string? value) (<= 1 (count value) 120))
    :finite-number (and (number? value) (Double/isFinite (double value)) (<= (abs (double value)) 3600.0))
    :nonnegative-number (and (valid-argument? :finite-number value) (<= 0 value))
    :positive-number (and (valid-argument? :finite-number value) (< 0 value) (<= value 30))
    :boolean (boolean? value)
    :percentage (and (integer? value) (<= 0 value 100)) false))

(defn- validate-command! [{:keys [op args] :as command}]
  (when (and (contains? command :expected-revision)
             (not (and (integer? (:expected-revision command)) (<= 0 (:expected-revision command)))))
    (throw (ex-info "Expected revision must be a nonnegative integer" {:code :invalid-argument})))
  (when-not (and (map? command) (keyword? op) (map? args))
    (throw (ex-info "Expected {:op keyword :args map}" {:code :invalid-command})))
  (if-let [schema (get core-commands op)]
    (when-not (and (= (set (keys args)) (set (keys schema)))
                   (every? (fn [[key kind]] (valid-argument? kind (get args key))) schema)
                   (or (not (#{:take/trim :mix/loop} op)) (< (:from args) (:to args))))
      (throw (ex-info "Invalid command arguments" {:code :invalid-argument :op op :schema schema})))
    (if-let [extension (get @extensions op)]
      (when-not ((:validate extension) args) (throw (ex-info "Invalid extension arguments" {:code :invalid-argument :op op})))
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
        (do (when-not (= command (:command existing))
              (throw (ex-info "Request id already used for another command" {:code :request-conflict :request-id id})))
            {:request-id id :status :known})
        (do
          (when-not @worker (throw (ex-info "Open the studio before sending commands" {:code :closed})))
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
      (let [ids (conj @completed-requests request-id) expired (drop-last 256 ids)]
        (swap! request-ledger #(apply dissoc % expired))
        (reset! completed-requests (vec (take-last 256 ids)))))))

(defn- execute-command! [{:keys [op args] :as command}]
  (validate-command! command)
  (when (and (contains? command :expected-revision) (not= (:expected-revision command) (:revision @project)))
    (throw (ex-info "Project changed; query before retrying this edit"
                    {:code :revision-conflict :expected (:expected-revision command) :actual (:revision @project)})))
  (when (and (or @session @armed) (not= op :transport/stop))
    (throw (ex-info "Recording owns the transport; stop it first" {:code :recording-busy})))
  ;; The live mix is a prepared immutable snapshot, not a live arranger yet.
  ;; Editing the source selection must not display a different WAV over old audio.
  (when (= "take" (namespace op)) (stop-mix!))
  (let [value (case op
    :mix/prepare (prepare-mix!)
    :mix/play (play-mix!)
    :mix/stop (stop-mix!)
    :mix/toggle (if (render! #(az/value mix-mode)) (stop-mix!) (do (prepare-mix!) (play-mix!)))
    :mix/loop
    (let [from (Math/round (* 48000.0 (double (:from args))))
          to (Math/round (* 48000.0 (double (:to args))))]
      (when (empty? @mix-sources) (throw (ex-info "Préparer le mix avant de boucler." {:code :mix-unprepared})))
      (when (or (>= from to) (> to (az/value mixer/duration))
                (not (mixer/set-loop! from to (:enabled args))))
        (throw (ex-info "La boucle doit rester dans le mix, avec A avant B." {:code :invalid-loop})))
      (render! #(do (az/set-value! loop-from (/ from 48000.0)) (az/set-value! loop-to (/ to 48000.0))))
      {:from-frame from :to-frame to :enabled (:enabled args)})
    :mix/clip
    (let [i (first (keep-indexed #(when (= (:id args) (:id %2)) %1) @mix-sources))]
      (when-not i (throw (ex-info "Unknown mix clip" {:code :not-found})))
      (when (az/value mixer/opened) (throw (ex-info "Stop mix before editing its plan" {:code :mix-busy})))
      (when-not (mixer/configure-clip! i (long (* 48000 (:start args))) (:gain args) (:pan args)
                                      (long (* 48000 (:fade args))) (:mute args) (:solo args))
        (throw (ex-info "Invalid mix clip settings" {:code :invalid-argument})))
      (swap! mix-sources update i merge args))
    (:project/undo :project/redo)
    (do (stop-mix!) (render! #(do (stop-voice!) (az/set-value! preview-node 4294967295)
                     (az/set-value! seek-seconds 0.0) (az/set-value! preview-paused false)))
        (let [next (files/history-project! project project-file (if (= op :project/undo) :undo :redo))]
          (reset! takes (:takes next)) (reset! display-cache nil) (reset! comparison nil)
          (let [entry (get (:takes next) (focused-id))
                label (or (:name (some #(when (= (:selected entry) (:path %)) %) (:history entry))) "")]
            (render! #(name! label)))
          (message! (if (= op :project/undo) "Modification annulée. Audio conservé." "Modification rétablie."))
          {:revision (:revision next)}))
    :transport/stop (finish-take!)
    :transport/pause (render! #(pause-preview!))
    :transport/rewind (render! #(position! 0.0))
    :transport/seek (render! #(position! (:seconds args)))
    :transport/play (play-transport!)
    :transport/toggle (if (render! #(playing-preview?)) (render! #(pause-preview!))
                         (play-transport!))
    :transport/launch (do (render! #(az/set-value! seek-seconds 0.0)) (preview!))
    :selection/passage
    (render! #(let [index (first (filter (fn [i] (= (:id args) (native-string (node-id i))))
                                       (range (az/value scene/passage-entity-count))))]
                (when-not index (throw (ex-info "Unknown passage" {:code :not-found :id (:id args)})))
                (az/set-value! selected index) (az/set-value! focus-scroll 0.0)))
    :view/zoom (render! #(zoom-at! (:factor args) 563.0))
    :view/pan (render! #(pan! (:seconds args)))
    :take/previous (select-take! false) :take/next (select-take! true)
    :take/name (do (let [bytes (.getBytes ^String (:name args) "UTF-8")]
                     (when (> (alength bytes) 120) (throw (ex-info "Name exceeds 120 UTF-8 bytes" {:code :invalid-argument})))
                     (render! #(name! (:name args))))
                   (edit-take! :name))
    :take/mark-a (edit-take! :a) :take/compare (edit-take! :compare)
    :take/trim (do (refresh-display!)
                   (render! #(do (az/set-value! trim-in (:from args)) (az/set-value! trim-out (:to args))))
                   (edit-take! :trim))
    :take/preferred (edit-take! :preferred) :take/publish (publish!) :take/recover (recover!)
    :record/dry (render! #(az/set-value! record-mode 1))
    :record/fx (render! #(az/set-value! record-mode 8))
    :record/toggle
    (do (render! #(az/set-value! record-enabled (- 1 (az/value record-enabled))))
        (message! (if (= 1 (render! #(az/value record-enabled)))
                    "REC prêt. Armer une piste, choisir l'entrée, puis Lecture."
                    "REC désactivé. Lecture seule.")))
    :record/enable (render! #(az/set-value! record-enabled (if (:enabled args) 1 0)))
    :playback/boost (render! #(do (az/set-value! audition-boost (:enabled args)) (update-audition-gain!)))
    :record/arm
    (render! #(let [index (first (filter (fn [i] (= (:id args) (native-string (node-id i))))
                                       (range (az/value scene/passage-entity-count))))]
                (when (or (empty? (:id args)) (nil? index))
                  (throw (ex-info "Unknown voiced passage" {:code :not-found})))
                (when (or (:enabled args) (= index (az/value record-track)))
                  (az/set-value! record-track (if (:enabled args) index 4294967295)))))
    :record/process (do (stop-mix!) (start-take! true))
    :routing/select
    (render! #(let [ins (az/value recorder/capture-count) outs (az/value recorder/playback-count)]
                (when-not (and (< (:source args) ins) (< (:return args) ins)
                               (< (:send args) outs) (< (:headphones args) outs))
                  (throw (ex-info "Audio device index is no longer available; refresh devices." {:code :not-found})))
                (when (not= (:headphones args) (az/value headphones))
                  (close-playback!) (mixer/close!) (az/set-value! preview-paused false))
                (doseq [[field key] [[microphone :source] [effects-output :send] [return-input :return] [headphones :headphones]]]
                  (az/set-value! field (get args key)))
                args))
    :routing/save (routing! :save) :routing/next (routing! :next) :routing/connect (routing! :connect)
    :routing/monitor (routing! :monitor)
    ((:handler (get @extensions op)) args))]
    {:op op :accepted? true :project-revision (:revision @project) :result value}))

(def ^:private ui-commands
  {1 :record/dry 2 :transport/stop 3 :record/process 4 :take/previous 5 :take/next
   6 :transport/toggle 7 :take/publish 8 :record/fx 10 :take/mark-a 11 :take/compare
   13 :take/preferred 14 :take/recover 20 :routing/save 21 :routing/next 22 :routing/connect
   23 :routing/monitor 26 :transport/launch 27 :project/undo 28 :project/redo 30 :mix/toggle
   34 :record/toggle})

(defn- ui-command [action]
  (case action
    (31 32 33)
    {:op :mix/loop
     :args (render! #(let [v (mixer/loop-state)
                          enabled (try (:enabled (az/value v)) (finally (az/close! v)))]
                       {:from (if (= action 32) (cursor-seconds) (az/value loop-from))
                        :to (if (= action 33) (cursor-seconds) (az/value loop-to))
                        :enabled (if (= action 31) (not enabled) enabled)}))}
    9 {:op :take/name :args {:name (native-string (render! #(entered-name)))}}
    12 {:op :take/trim :args (render! #(hash-map :from (az/value trim-in) :to (az/value trim-out)))}
    (when-let [op (get ui-commands action)] {:op op :args {}})))

(defn- process-command! [command ticket]
  ;; Command rejection is not an audio-device failure: never stop an existing capture here.
  (try
    (render! #(az/set-value! busy 1))
    (let [value (binding [*command-worker?* true] (execute-command! command))]
      (if ticket (complete-request! ticket {:status :done :value value})
          (emit-event! {:type :command/completed :source :ui :op (:op command) :status :done})))
    (catch Throwable e
      (if ticket (complete-request! ticket {:status :error :error (merge {:message (ex-message e)} (ex-data e))})
          (emit-event! {:type :command/completed :source :ui :op (:op command) :status :error :message (ex-message e)}))
      (message! (ex-message e)))
    (finally (render! #(do (az/set-value! busy (if (or @session @armed) 1 0))
                           (az/set-value! capture-phase (cond @session 2 @armed 1 :else 0)))))))

(defn open! []
  (when @worker (throw (ex-info "Atelier déjà ouvert." {})))
  (reset! project (files/load-project! project-file index-file))
  (reset! takes (:takes @project))
  (reset! presets (files/read-state presets-file {}))
  (reset! display-cache nil)
  (when-not (recorder/initialize!) (throw (ex-info "Audio device enumeration failed" {})))
  (render! #(initialize-listen-output!))
  (doseq [[capture? field total] [[true return-input (az/value recorder/capture-count)]
                                [false effects-output (az/value recorder/playback-count)]]
          index (range total)
          :when (= "BlackHole 16ch" (native-string (recorder/device-name capture? index)))]
    (render! #(az/set-value! field index)))
  (render! #(attach!))
  (when-not (render! #(az/value attached)) (throw (ex-info "Studio window could not open" {})))
  (render! #(az/set-value! page 0))
  (when-not @worker
    (reset! worker
      (future
        (try
          (loop [display-tick 0]
            (let [action (take-action!)]
              (try
                (if (pos? action)
                  (when-let [command (ui-command action)] (process-command! command nil))
                  (when-let [ticket (.poll command-queue)] (process-command! (:command ticket) ticket)))
                (when-let [{:keys [until action]} @armed]
                  (when (>= (System/currentTimeMillis) until)
                    (reset! armed nil)
                    (if (= action 8) (start-live-take!) (start-take! false))))
                (checkpoint! false)
                (when (and @session (recorder/done?) (render! #(busy?)))
                  (save-take!) (render! #(do (az/set-value! busy 0) (az/set-value! capture-phase 0))))
                (when (zero? display-tick) (refresh-display!))
                (catch Throwable e
                  (recorder/stop!)
                  (reset! armed nil)
                  ;; An encoding failure must not allow a new take to overwrite unsaved PCM.
                  (render! #(do (az/set-value! busy (if @session 1 0))
                                (az/set-value! capture-phase (if @session 2 0))))
                  (message! (.getMessage e)))))
            (Thread/sleep 40)
            (recur (mod (inc display-tick) 6)))
          (catch InterruptedException _)))))
  :opened)

(defn close! []
  (when @session (throw (ex-info "Arrêter et sauver la prise avant de fermer l'atelier." {})))
  (stop-mix!)
  (when-let [f @worker] (future-cancel f) (reset! worker nil))
  (locking command-queue
    (loop []
      (when-let [ticket (.poll command-queue)]
        (complete-request! ticket {:status :error :error {:code :closed :message "Studio closed before execution"}})
        (recur))))
  (reset! armed nil)
  (recorder/stop!)
  (render! #(do (detach!) (az/set-value! busy 0) (az/set-value! capture-phase 0)))
  :closed)
