(ns la-professeure.studio-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak] [aguafria.std.mem :as mem]
            [la-professeure.scene :as scene]
            [la-professeure.gpu :as gpu]
            [la-professeure.core :as core]
            [la-professeure.miniaudio :as audio]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [la-professeure.tools.studio :as studio]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.takes-test]
            [la-professeure.studio-api-test]
            [la-professeure.mixer-test]
            [la-professeure.tools.recorder :as recorder]))

(az/configure! {:module-zig-args
                (assoc (:module-zig-args (az/configuration)) "la-professeure.studio-test"
                       [(str "-I" (io/file (la-professeure.build/root) "build/vendor/miniaudio"))])})

;; Explicit live-window QA helpers; do not open hardware windows in unit tests.
(az/defn separate-window-contract? :- :bool []
  (and studio/attached (ak/!= studio/studio-window scene/window)
       gpu/initialized (az/field studio/renderer initialized)
       (ak/!= gpu/device (az/field studio/renderer device))
       (ak/!= gpu/surface (az/field studio/renderer surface))
       (ak/!= gpu/mapped-mesh-vertices (az/field studio/renderer mapped-mesh-vertices))))

(az/defn game-voice-cursor :- :u64 []
  (let [^{:var :u64} cursor 0]
    (when scene/voice-ready
      (set! _ (audio/ma_sound_get_cursor_in_pcm_frames
                (ak/& (az/index scene/voices scene/voice-slot)) (ak/& cursor))))
    cursor))

(az/defn place-windows! :- :void []
  (glfw/glfwSetWindowPos scene/window 0 60)
  (glfw/glfwSetWindowPos studio/studio-window 610 300))

(az/defn close-studio-window! :- :void []
  (glfw/glfwSetWindowShouldClose studio/studio-window 1))

(az/defn studio-visible? :- :bool []
  (and (ak/!= studio/studio-window ak/null)
       (ak/!= (glfw/glfwGetWindowAttrib studio/studio-window glfw/GLFW_VISIBLE) 0)))

(az/defn studio-focused? :- :bool []
  (and (ak/!= studio/studio-window ak/null)
       (ak/!= (glfw/glfwGetWindowAttrib studio/studio-window glfw/GLFW_FOCUSED) 0)))

(az/defn game-suppressed? :- :bool [] scene/studio-audio-suppressed)
(az/defn game-muted? :- :bool [] scene/audio-muted)
(az/defn set-game-muted! :- :void [[muted :bool]] (set! scene/audio-muted muted))
(az/defconst volume-api (ak/cImport (ak/cInclude "miniaudio.h")))
(az/defn game-track-volume :- :f32 []
  (if scene/audio-ready
    ((az/field volume-api ma_sound_get_volume) (ak/ptrCast (ak/& (az/index scene/tracks scene/active-track)))) -1.0))
(az/defn game-dialogue-volume :- :f32 []
  (if scene/voice-ready
    ((az/field volume-api ma_sound_get_volume) (ak/ptrCast (ak/& (az/index scene/voices scene/voice-slot)))) -1.0))
(az/defn preview-volume :- :f32 []
  (if studio/voice-ready
    ((az/field volume-api ma_sound_get_volume) (ak/ptrCast (ak/& (az/index studio/voices studio/voice-slot)))) -1.0))

(az/defn independent-playback? :- :bool []
  (and studio/playback-ready
       (ak/!= (ak/& studio/playback-engine) (ak/& scene/engine))
       (ak/!= ((az/field volume-api ma_engine_get_device) (ak/ptrCast (ak/& studio/playback-engine)))
              ((az/field volume-api ma_engine_get_device) (ak/ptrCast (ak/& scene/engine))))))

(az/defn actual-playback-device :- [:slice-const :u8] []
  (when (ak/! studio/playback-ready) (ak/return ""))
  (let [^{:zig/type [:* recorder/Device]} device (ak/ptrCast ((az/field volume-api ma_engine_get_device) (ak/ptrCast (ak/& studio/playback-engine))))
        ^{:var :usize} length 0]
    (ak/while (and (< length 255) (ak/!= (az/index (az/field (az/field device playback) name) length) 0))
      (set! length (+ length 1)))
    (az/slice (az/field (az/field device playback) name) 0 length)))

(az/defn actual-mix-device :- [:slice-const :u8] []
  (let [device (ak/& mixer/device) ^{:var :usize} length 0]
    (ak/while (and (< length 255) (ak/!= (az/index (az/field (az/field device playback) name) length) 0))
      (set! length (+ length 1)))
    (az/slice (az/field (az/field device playback) name) 0 length)))

(deftest audition-gain-is-bounded-and-opt-in
  (let [boost (az/value studio/audition-boost) peak (az/value studio/audition-source-peak)]
    (try
      (doseq [[enabled source expected] [[false 0.0003 1.0] [true 0.0 1.0]
                                        [true 0.00001 1000.0] [true 0.001 200.0]
                                        [true 1.0 1.0]]]
        (az/set-value! studio/audition-boost enabled)
        (az/set-value! studio/audition-source-peak source)
        (studio/update-audition-gain!)
        (is (< (abs (- expected (az/value studio/audition-gain))) 0.001)))
      (finally (az/set-value! studio/audition-boost boost)
               (az/set-value! studio/audition-source-peak peak)
               (studio/update-audition-gain!)))))

(deftest game-audio-focus-isolation
  ;; Render-thread test: no device is opened and no user mute setting is lost.
  (let [suppressed (game-suppressed?) muted (game-muted?) preview (preview-volume)]
    (try
      (set-game-muted! false)
      (studio/suppress-game-audio! false)
      (studio/suppress-game-audio! true)
      (is (game-suppressed?)) (is (false? (game-muted?)))
      (when (>= (game-track-volume) 0) (is (zero? (game-track-volume))))
      (when (>= (game-dialogue-volume) 0) (is (zero? (game-dialogue-volume))))
      (is (= preview (preview-volume)))
      (set-game-muted! true)
      (studio/suppress-game-audio! false)
      (is (false? (game-suppressed?))) (is (game-muted?))
      (when (>= (game-track-volume) 0) (is (zero? (game-track-volume))))
      (when (>= (game-dialogue-volume) 0) (is (zero? (game-dialogue-volume))))
      (set-game-muted! false)
      (studio/suppress-game-audio! true) (studio/suppress-game-audio! false)
      (when (>= (game-track-volume) 0) (is (< (abs (- 0.35 (game-track-volume))) 0.0001)))
      (when (>= (game-dialogue-volume) 0) (is (< (abs (- 0.8 (game-dialogue-volume))) 0.0001)))
      (finally
        (set-game-muted! muted)
        (studio/suppress-game-audio! (not suppressed))
        (studio/suppress-game-audio! suppressed)))))

(deftest playhead-device-pixel-alignment
  (let [fields [studio/timeline-start studio/timeline-seconds studio/framebuffer-scale]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/timeline-start 0.0) (az/set-value! studio/timeline-seconds 30.0)
      (doseq [scale [1.0 1.5 2.0]]
        (az/set-value! studio/framebuffer-scale scale)
        (let [xs (mapv studio/playhead-x [0.0 1.0 1.01 1.02 1.03 2.0 15.0 30.0])]
          (is (apply <= xs))
          (doseq [x xs] (is (< (abs (- (* x scale) (Math/rint (* x scale)))) 0.0001)))
          (is (= (studio/playhead-x 1.234) (studio/playhead-x 1.234)))))
      (finally (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

(deftest french-typewriter-prefixes
  (let [before (az/value scene/reveal-remaining)]
    (try
      (doseq [[budget expected] [[0 0] [1 2] [2 4] [3 7] [4 8] [40 8]]]
        (az/set-value! scene/reveal-remaining budget)
        ;; é / œ / curly apostrophe / a: count characters, never cut UTF-8 bytes.
        (is (= expected (scene/revealed-prefix! "éœ’a"))))
      (finally (az/set-value! scene/reveal-remaining before)))))

(deftest leaf-dialogue-choices
  ;; Read-only with respect to visited flags and authored Markdown.
  (let [parent (az/value scene/story-parent)
        indices (range (scene/passage-count))
        node-text (fn [i] (#'studio/native-string (scene/story-text i)))
        find-node (fn [text] (first (filter #(= text (node-text %)) indices)))]
    (try
      (doseq [[text choices] [["Se retourner." ["Se retourner." "Inspecter la voiture."]]
                              ["Inspecter la voiture." ["Ouvrir la poignée." "Frapper à la porte."]]
                              ["Ouvrir la poignée." ["Lire l’autocollant."]]
                              ["Frapper à la porte." ["Ouvrir la poignée." "Frapper à la porte."]]]]
        (let [index (find-node text)]
          (is (some? index) text)
          (az/set-value! scene/story-parent index)
          (is (= choices (mapv #(node-text (scene/story-choice %)) (range 1 (inc (count choices))))))
          (is (= 4294967295 (scene/story-choice (inc (count choices)))))
          (is (= index (az/value scene/story-parent)) "Resolving choices retains the response")))
      (is (false? (scene/story-choice-visited? 4294967295)))
      (finally (az/set-value! scene/story-parent parent)))))

(defn- require-isolated-recorder! []
  (when @studio/worker
    (throw (ex-info "Run destructive audio tests in a separate test process, not the open studio." {}))))

(deftest font-atlas-transparent-gutters
  (let [pixels (java.nio.file.Files/readAllBytes (.toPath (io/file "resources/demo/atlas.rgba")))]
    (is (= (* 2048 1536 4) (alength pixels)))
    (is (every? zero?
          (for [code (range 256) y (range 80) x (range 64)
                :when (or (< x 2) (>= x 62) (< y 2) (>= y 78))]
            (aget pixels (+ 3 (* 4 (+ (+ (* (mod code 32) 64) x)
                                        (* 2048 (+ (* (quot code 32) 80) y)))))))))))

(defn verify-live-windows!
  "Run explicitly with studio open. Verifies independent ownership and simultaneous progress."
  []
  (let [sample #(let [result @(core/on-render!
                               (fn [] {:independent? (separate-window-contract?)
                                       :game (az/value scene/rendered-frames)
                                       :studio (az/value studio/rendered-frames)
                                       :time (az/value scene/elapsed)}))]
                 (when-let [error (:error result)] (throw error)) (:value result))
        before (sample) _ (Thread/sleep 350) after (sample)]
    (assert (:independent? after) "Windows must own distinct Vulkan resources")
    (assert (< (:game before) (:game after)) "Game must keep updating")
    (assert (< (:studio before) (:studio after)) "Studio must keep rendering")
    (assert (< (:time before) (:time after)) "Game animation must keep advancing")
    {:before before :after after}))

(deftest publication-audio-validation
  (require-isolated-recorder!)
  (let [directory (java.nio.file.Files/createTempDirectory "la-professeure-validation-"
                     (make-array java.nio.file.attribute.FileAttribute 0))]
    (doseq [[label sample accepted?] [["quiet" 0.05 true] ["silent" 0.0 false]
                                    ["clipped" 1.1 false] ["nan" Float/NaN false]]]
      (let [file (.resolve directory (str label ".wav"))
            buffer (doto (java.nio.ByteBuffer/allocate 76) (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
        (.put buffer (.getBytes "RIFF" "US-ASCII")) (.putInt buffer 68)
        (.put buffer (.getBytes "WAVEfmt " "US-ASCII")) (.putInt buffer 16)
        (.putShort buffer (short 3)) (.putShort buffer (short 2))
        (.putInt buffer 48000) (.putInt buffer 384000) (.putShort buffer (short 8)) (.putShort buffer (short 32))
        (.put buffer (.getBytes "data" "US-ASCII")) (.putInt buffer 32)
        (dotimes [_ 8] (.putFloat buffer (float sample)))
        (java.nio.file.Files/write file (.array buffer) (make-array java.nio.file.OpenOption 0))
        (is (= accepted? (recorder/validate-take! (str file))) label)))))

(deftest native-routing-and-wave-roundtrip
  (require-isolated-recorder!)
  (is (recorder/routing-test!))
  (is (recorder/load-dry! "resources/demo/lesson.wav"))
  (is (recorder/validate-take! "resources/demo/lesson.wav"))
  (is (false? (recorder/validate-take! "resources/not-a-real-take.wav")))
  (let [frames (az/value recorder/dry-frames)
        directory (java.nio.file.Files/createTempDirectory "la-professeure-recorder-" (make-array java.nio.file.attribute.FileAttribute 0))
        file (io/file (str directory) "dry.wav")]
    (is (pos? frames))
    (is (.createNewFile file))
    (is (recorder/write-take! (str file) false))
    (is (recorder/validate-take! (str file)))
    (is (> (.length file) (* frames 8)))
    (is (recorder/load-dry! (str file)))
    (is (= frames (az/value recorder/dry-frames))))
  ;; Context has not been initialized: no microphone or output can open.
  (is (false? (recorder/start! 999 999 2 48000))))

(deftest shared-flecs-dialogue-lifecycle
  (is (scene/reload-story!))
  (let [count (az/value scene/passage-entity-count)
        ids (az/value scene/passage-entities)]
    (is (= count (scene/passage-count)))
    (dotimes [_ 3] (is (scene/reload-story!)) (is (= count (scene/passage-count))))
    ;; First voiced passage keeps its identity across reloads.
    (is (= (nth ids 1) (nth (az/value scene/passage-entities) 1)))))

(deftest live-effects-routing-and-paired-takes
  (require-isolated-recorder!)
  (is (recorder/live-routing-test!))
  (is (false? (recorder/start-live! 999 999 999 48000)))
  (let [directory (java.nio.file.Files/createTempDirectory "la-professeure-live-"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (doseq [[kind processed? frames] [["dry" false 4] ["wet" true 6]]]
      (let [file (io/file (str directory) (str kind ".wav"))]
        (is (.createNewFile file))
        (is (recorder/write-take! (str file) processed?))
        (is (recorder/validate-take! (str file)))
        (is (= frames (az/value recorder/measured-frames)))))))

(az/defn monitor-isolation! :- :bool []
  (let [^{:var [:array 16 :f32]} input (mem/zeroes (az/type [:array 16 :f32]))
        ^{:var [:array 2 :f32]} output ak/undefined]
    (set! (az/index input 0) 0.9) (set! (az/index input 1) 0.9)
    (set! (az/index input 2) 0.5) (set! (az/index input 3) -4.0)
    (ak/atomicStore :u32 (ak/& recorder/monitor-gain) 100 :.release)
    (recorder/process-monitor! (ak/& output) (ak/& input) 1)
    (when (or (ak/!= (az/index output 0) 0.25) (ak/!= (az/index output 1) -0.5)) (ak/return false))
    (recorder/process-monitor! (ak/& output) ak/null 1)
    (ak/atomicStore :u32 (ak/& recorder/monitor-gain) 15 :.release)
    (and (ak/== (az/index output 0) 0.0) (ak/== (az/index output 1) 0.0))))

(deftest monitoring-and-native-checkpoints
  (require-isolated-recorder!)
  (is (monitor-isolation!))
  (is (false? (recorder/start-monitor! 999 999)))
  (is (recorder/live-routing-test!))
  (let [file (java.nio.file.Files/createTempFile "professeure-checkpoint-" ".pcm"
               (make-array java.nio.file.attribute.FileAttribute 0))]
    (is (= 2 (recorder/journal! (str file) false 0)))
    (is (= 16 (.length (.toFile file))))
    (is (= 2 (recorder/journal! (str file) false 2)))
    (is (= 16 (.length (.toFile file))))
    (is (= 0 (recorder/journal! (str file) false 5))))
  (is (= 0.0 (recorder/wave-bin false 128)))
  (recorder/reset-meters!) (recorder/meter-input! 1.2)
  (is (= 1 (az/value recorder/clipped)))
  (recorder/reset-meters!) (is (= 0 (az/value recorder/clipped))))

(deftest daw-timeline-and-nondestructive-selection
  ;; Run on the render thread if the workspace is already open. No audio device is opened.
  (let [fields [studio/timeline-start studio/timeline-seconds studio/trim-in studio/trim-out studio/trim-drag]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/timeline-start 0.0) (az/set-value! studio/timeline-seconds 30.0)
      (is (= 0.0 (studio/time-at -100.0)))
      (is (= 0.0 (studio/time-at 242.0)))
      (is (= 15.0 (studio/time-at 563.0)))
      (is (= 30.0 (studio/time-at 900.0)))
      (studio/zoom! 0.5) (is (= 15.0 (az/value studio/timeline-seconds)))
      (studio/zoom! 0.00001) (is (= 2.0 (az/value studio/timeline-seconds)))
      (az/set-value! studio/timeline-start 58.0)
      (is (= 59.0 (studio/time-at 563.0)))
      (studio/zoom! 1000.0)
      (is (= 60.0 (az/value studio/timeline-seconds)))
      (is (= 0.0 (az/value studio/timeline-start)))
      (az/set-value! studio/trim-in 0) (az/set-value! studio/trim-out 100)
      (az/set-value! studio/trim-drag 1) (studio/trim-at! 1000.0)
      (is (= 99 (az/value studio/trim-in)))
      (az/set-value! studio/trim-drag 2) (studio/trim-at! -100.0)
      (is (= 100 (az/value studio/trim-out)))
      (az/set-value! studio/trim-in 10) (studio/trim-at! 563.0)
      (is (= 50 (az/value studio/trim-out)))
      (az/set-value! studio/trim-drag 1) (studio/trim-at! -100.0)
      (is (= 0 (az/value studio/trim-in)))
      (finally (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

(deftest daw-pointer-navigation
  (let [fields [studio/timeline-start studio/timeline-seconds studio/follow-playhead
                studio/mouse-x studio/mouse-y studio/focus-scroll studio/focus-height
                studio/page studio/track-scroll studio/track-offset]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/timeline-start 10.0) (az/set-value! studio/timeline-seconds 30.0)
      (let [anchor (studio/time-at 563.0)]
        (studio/zoom-at! 0.5 563.0)
        (is (= anchor (studio/time-at 563.0)))
        (is (= 15.0 (az/value studio/timeline-seconds))))
      (studio/pan! 1000.0) (is (= 45.0 (az/value studio/timeline-start)))
      (studio/pan! -1000.0) (is (= 0.0 (az/value studio/timeline-start)))
      (is (false? (az/value studio/follow-playhead)))
      (az/set-value! studio/mouse-x 300.0) (az/set-value! studio/mouse-y 500.0)
      (az/set-value! studio/focus-scroll 0.0) (az/set-value! studio/focus-height 500.0)
      (studio/scroll-by! 0.0 -2.0 false false)
      (is (= 40.0 (az/value studio/focus-scroll)))
      (is (= 0.0 (az/value studio/timeline-start)))
      (az/set-value! studio/mouse-y 200.0) (az/set-value! studio/page 0)
      (az/set-value! studio/track-offset 0) (az/set-value! studio/track-scroll 0.0)
      (dotimes [_ 4] (studio/scroll-by! 0.0 -0.3 false false))
      (is (= 1 (az/value studio/track-offset)))
      (studio/scroll-by! -1.0 0.0 false false)
      (is (< 0.59 (az/value studio/timeline-start) 0.61))
      (finally (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

(az/defn keyboard-contract! :- :bool []
  (let [focus studio/name-focus length studio/name-length old-busy studio/busy old-pending studio/pending
        visible studio/attached first-byte (az/index studio/edit-name 0)]
    (ak/defer (do (set! studio/name-focus focus) (set! studio/name-length length)
                  (set! studio/busy old-busy) (set! studio/pending old-pending)
                  (set! studio/attached visible) (set! (az/index studio/edit-name 0) first-byte)))
    (set! studio/attached true) (set! studio/name-focus false)
    (set! studio/busy 0) (set! studio/pending 0)
    (studio/key-event! ak/null 32 0 1 0)
    (when (ak/!= (studio/take-action!) 6) (ak/return false))
    (set! studio/busy 0) (set! studio/name-focus true) (set! studio/name-length 0)
    (studio/key-event! ak/null 32 0 1 0) (studio/typed! ak/null 32)
    (when (or (ak/!= (studio/take-action!) 0) (ak/!= studio/name-length 1)) (ak/return false))
    (studio/key-event! ak/null 259 0 1 0)
    (when (ak/!= studio/name-length 0) (ak/return false))
    (set! studio/name-focus false)
    (studio/key-event! ak/null 90 0 1 8)
    (when (ak/!= (studio/take-action!) 27) (ak/return false))
    (set! studio/busy 0)
    (studio/key-event! ak/null 90 0 1 9)
    (when (ak/!= (studio/take-action!) 28) (ak/return false))
    (set! studio/busy 0)
    (studio/key-event! ak/null 32 0 2 0)
    (when (ak/!= (studio/take-action!) 0) (ak/return false))
    (studio/key-event! ak/null 256 0 1 0)
    (ak/== (studio/take-action!) 2)))

(deftest native-keyboard-focus-and-repeat
  (is (keyboard-contract!)))

(deftest scrollbar-grab-preserves-position
  (let [before (az/value studio/bar-grab)]
    (try
      (az/set-value! studio/bar-grab 23.0)
      (is (= 7 (studio/track-offset-at (+ 169.0 23.0 (* 0.5 (- 262.0 (* 262.0 (/ 6.0 20.0))))) 20)))
      (is (= 0 (studio/track-offset-at -500.0 20)))
      (is (= 14 (studio/track-offset-at 1000.0 20)))
      (is (= 0 (studio/track-offset-at 200.0 0)))
      (is (= 0 (studio/track-offset-at 200.0 6)))
      (finally (az/set-value! studio/bar-grab before)))))

(defn -main [& _]
  (let [result (run-tests 'la-professeure.studio-test 'la-professeure.takes-test 'la-professeure.studio-api-test 'la-professeure.mixer-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
