(ns la-professeure.studio-test
  (:require [aguafria.std]
            [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak] [aguafria.std.mem :as mem]
            [la-professeure.scene :as scene]
            [la-professeure.gpu :as gpu]
            [la-professeure.core :as core]
            [la-professeure.recording-tool :as story]
            [la-professeure.miniaudio :as audio]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.mesh :as mesh]
            [la-professeure.tools.studio :as studio]
            [la-professeure.tools.takes :as files]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.takes-test]
            [la-professeure.studio-api-test]
            [la-professeure.mixer-test]
            [la-professeure.tools.recorder :as recorder]))

(az/configure! {:module-zig-args
                (assoc (:module-zig-args (az/configuration)) "la-professeure.studio-test"
                       [(str "-I" (io/file (la-professeure.build/root) "build/vendor/miniaudio"))])})

;; Explicit live-window QA helpers; do not open hardware windows in unit tests.
(az/defn focus-game-input-qa!
  "Opt-in: focus the existing game window without starting audio or a recording."
  :- :void []
  (when (ak/!= scene/window ak/null)
    (glfw/glfwShowWindow scene/window)
    (glfw/glfwFocusWindow scene/window)))

(az/defn game-input-state-qa :- :u32 []
  (when (ak/== scene/window ak/null)
    (ak/return 0))
  (+ (ak/as :u32 (if (ak/== (glfw/glfwGetWindowAttrib scene/window glfw/GLFW_FOCUSED)
                           glfw/GLFW_TRUE) 1 0))
     (ak/as :u32 (if (ak/== (glfw/glfwGetInputMode scene/window glfw/GLFW_STICKY_KEYS)
                           glfw/GLFW_TRUE) 2 0))))

(defmacro set-studio-state! [bindings]
  ;; Exercise the production macro without exposing it as another public API.
  (apply #'studio/set-state! [&form &env bindings]))

(az/defn input-check-meter-contract :- :u32 []
  (when (recorder/input-check-active?) (ak/return 1))
  (let [channels recorder/input-check-channels
        offset recorder/input-check-offset
        peak recorder/input-check-peak
        held recorder/input-check-held
        frames recorder/input-check-frames
        dry-frames recorder/dry-frames
        wet-frames recorder/recorded-frames
        ^:var samples (ak/as (az/type [:array 4 :f32]) [0.25 -0.5 0.125 0.0])]
    (ak/defer
      (set-studio-state! [recorder/input-check-channels channels
                          recorder/input-check-offset offset
                          recorder/input-check-peak peak
                          recorder/input-check-held held
                          recorder/input-check-frames frames]))
    (set-studio-state! [recorder/input-check-channels 2
                        recorder/input-check-offset 0
                        recorder/input-check-peak 0
                        recorder/input-check-held 0
                        recorder/input-check-frames 0])
    (recorder/process-input-check! (ak/& samples) 2)
    (when (or (ak/!= recorder/input-check-peak 500000)
              (ak/!= recorder/input-check-held 500000)
              (ak/!= recorder/input-check-frames 2))
      (ak/return 2))
    (recorder/process-input-check! ak/null 4)
    (when (or (ak/!= recorder/input-check-peak 0)
              (ak/!= recorder/input-check-held 500000)
              (ak/!= recorder/input-check-frames 6))
      (ak/return 3))
    (when (or (ak/!= dry-frames recorder/dry-frames)
              (ak/!= wet-frames recorder/recorded-frames))
      (ak/return 4)))
  0)

(deftest input-check-meters-without-recording
  (is (zero? (input-check-meter-contract))))

(az/defn interrupt-recorder-device-qa!
  "Opt-in fault injection: stop only Studio's owned device, not an OS device."
  :- :bool [[preflight? :bool]]
  (when (if preflight?
          (ak/! (recorder/input-check-active?))
          (ak/! recorder/running))
    (ak/return false))
  (ak/== ((az/field recorder/api ma_device_stop)
           (if preflight? (ak/& recorder/input-check-device) (ak/& recorder/device)))
         0))

(defn interrupt-owned-device-qa! [preflight?]
  (assert preflight? "Live recording forbids arbitrary extension commands; use the isolated capture test")
  (let [op (keyword "qa" (str "stop-device-" (java.util.UUID/randomUUID)))]
    (studio/register-command! op
      {:description "Stop only Studio's owned device to test unexpected-stop recovery"
       :validate empty?
       :handler (fn [_] (interrupt-recorder-device-qa! preflight?))})
    (try
      (let [result (studio/command! {:op op})]
        (assert (= :done (:status result)))
        (assert (true? (get-in result [:value :result])))
        :stopped)
      (finally
        (studio/unregister-command! op)))))

(az/defn interrupt-listening-output-qa! :- :bool [[mix? :bool]]
  (when (if mix? (ak/! mixer/opened) (ak/! studio/playback-ready))
    (ak/return false))
  (let [device (if mix?
                 (ak/& mixer/device)
                 ((az/field recorder/api ma_engine_get_device)
                   (ak/ptrCast (ak/& studio/playback-engine))))]
    (ak/== ((az/field recorder/api ma_device_stop) device) 0)))

(az/defn separate-window-contract? :- :bool []
  (and studio/attached (ak/!= studio/studio-window scene/window)
       gpu/initialized (az/field studio/renderer initialized)
       (ak/!= gpu/device (az/field studio/renderer device))
       (ak/!= gpu/surface (az/field studio/renderer surface))
       (ak/!= gpu/mapped-mesh-vertices (az/field studio/renderer mapped-mesh-vertices))))

(az/defn resize-extent-contract :- :u32 []
  (let [^{:var glfw/VkSurfaceCapabilitiesKHR} caps (mem/zeroes (az/type glfw/VkSurfaceCapabilitiesKHR))]
    (set! (az/field caps currentExtent) (glfw/VkExtent2D {:width 0xffffffff :height 0xffffffff}))
    (set! (az/field caps minImageExtent) (glfw/VkExtent2D {:width 100 :height 80}))
    (set! (az/field caps maxImageExtent) (glfw/VkExtent2D {:width 4096 :height 2160}))
    (let [zero (gpu/choose-extent caps (glfw/VkExtent2D {:width 0 :height 900}))
          low (gpu/choose-extent caps (glfw/VkExtent2D {:width 50 :height 40}))
          high (gpu/choose-extent caps (glfw/VkExtent2D {:width 8000 :height 4000}))
          normal (gpu/choose-extent caps (glfw/VkExtent2D {:width 2200 :height 1520}))]
      (when (or (ak/!= (az/field zero width) 0) (ak/!= (az/field zero height) 0)) (ak/return 1))
      (when (or (ak/!= (az/field low width) 100) (ak/!= (az/field low height) 80)) (ak/return 2))
      (when (or (ak/!= (az/field high width) 4096) (ak/!= (az/field high height) 2160)) (ak/return 3))
      (when (or (ak/!= (az/field normal width) 2200) (ak/!= (az/field normal height) 1520)) (ak/return 4)))
    (set! (az/field caps currentExtent) (glfw/VkExtent2D {:width 1600 :height 1000}))
    (let [fixed (gpu/choose-extent caps (glfw/VkExtent2D {:width 2200 :height 1520}))
          zero (gpu/choose-extent caps (glfw/VkExtent2D {:width 2200 :height 0}))]
      (when (or (ak/!= (az/field fixed width) 1600) (ak/!= (az/field fixed height) 1000)) (ak/return 5))
      (when (or (ak/!= (az/field zero width) 0) (ak/!= (az/field zero height) 0)) (ak/return 6))))
  (when (or (ak/! (gpu/resize-result? glfw/VK_SUBOPTIMAL_KHR))
            (ak/! (gpu/resize-result? glfw/VK_ERROR_OUT_OF_DATE_KHR))
            (gpu/resize-result? glfw/VK_SUCCESS)
            (gpu/resize-result? glfw/VK_ERROR_DEVICE_LOST)) (ak/return 7))
  0)

(deftest swapchain-extent-and-result-contract
  (is (= 0 (resize-extent-contract)) "Extent clamping, minimized surfaces and recoverable WSI results"))

;; Explicit opt-in renderer QA; not part of the hardware-free unit suite.
;; Run only on core/on-render! and restore both window sizes afterwards.
(az/defn- resize-window-native! :- :void [[studio? :bool] [width :u32] [height :u32]]
  (glfw/glfwSetWindowSize (if studio? studio/studio-window scene/window)
                         (ak/intCast width) (ak/intCast height)))

(az/defn studio-renderer-snapshot :- gpu/RendererSnapshot []
  (gpu/swap-context! (ak/& studio/renderer))
  (ak/defer (gpu/swap-context! (ak/& studio/renderer)))
  (gpu/renderer-snapshot))

(az/defn studio-resize-count :- :u64 [] (az/field studio/renderer resize-count))

(az/defn studio-iconified? :- :bool []
  (ak/!= (glfw/glfwGetWindowAttrib studio/studio-window glfw/GLFW_ICONIFIED) 0))

(az/defn- independent-frame-callbacks? :- :bool []
  ;; Both callbacks must link together and match FrameBuilder's C ABI. Neither
  ;; needs to export the same unqualified linker symbol into this shared library.
  (let [^{:zig/type gpu/FrameBuilder} game-callback (ak/& scene/build-frame)
        ^{:zig/type gpu/FrameBuilder} studio-callback (ak/& studio/build-frame)]
    (ak/!= game-callback studio-callback)))

(deftest independent-frame-callback-linkage
  (is (independent-frame-callbacks?)))

(az/defn- capture-window-native! :- :usize
  [[studio? :bool]
   [output [:c-pointer :u8]]
   [capacity :usize]]
  (when studio?
    (gpu/swap-context! (ak/& studio/renderer)))
  (ak/defer (when studio?
              (gpu/swap-context! (ak/& studio/renderer))))
  (gpu/capture-frame! output capacity))

(az/defn- capture-format-native :- :u32 [[studio? :bool]]
  (ak/intCast (if studio?
                (az/field studio/renderer swapchain-format)
                gpu/swapchain-format)))

(defn- capture-window-pixels!
  "Render-thread implementation; return copied pixels, never borrowed GPU memory."
  [studio?]
  (assert (not (studio/busy?)) "Capture QA is only allowed while recording is idle")
  (assert (zero? (az/value studio/capture-phase)) "Capture QA cannot interrupt count-in or FX tail")
  (let [snapshot (az/value (if studio? (studio-renderer-snapshot) (gpu/renderer-snapshot)))
        capacity (* 4 (:width snapshot) (:height snapshot))]
    (when-not (and (:initialized snapshot) (pos? capacity))
      (throw (ex-info "Capture requires an initialized, visible renderer" snapshot)))
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [buffer (.allocate arena (long capacity) 16)
            copied (capture-window-native! studio? buffer capacity)
            after (az/value (if studio? (studio-renderer-snapshot) (gpu/renderer-snapshot)))
            renderer (if studio? (az/value studio/renderer) nil)]
        (when-not (= copied (* 4 (:width after) (:height after)))
          (throw (ex-info "GPU capture unavailable (surface/format/resize/capacity)"
                          {:copied copied :before snapshot :after after})))
        {:width (:width after)
         :height (:height after)
         :frames (:frames after)
         :format (capture-format-native studio?)
         :mode (when studio? (az/value studio/workspace-mode))
         :selected (when studio? (az/value studio/selected))
         :vertices (if studio? (:mesh-vertex-count renderer) (az/value gpu/mesh-vertex-count))
         :pixels (.toArray (.asSlice buffer 0 copied) java.lang.foreign.ValueLayout/JAVA_BYTE)}))))

(defn capture-window-qa!
  "Off-render opt-in QA: copy the actual Vulkan color attachment to a PNG.
   This verifies GPU rendering, not macOS composition or physical mouse input.
   Recreates only the chosen window's targets; never records or publishes audio."
  [studio? path]
  (let [result (deref (core/on-render! #(capture-window-pixels! studio?)) 20000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Timed out waiting for render-thread GPU capture" {})))
    (when-let [error (:error result)]
      (throw error))
    (let [{:keys [width height format pixels] :as capture} (:value result)
          bgra? (contains? #{44 50} format)
          image (java.awt.image.BufferedImage.
                 width height java.awt.image.BufferedImage/TYPE_INT_RGB)
          target (.getData ^java.awt.image.DataBufferInt
                           (.getDataBuffer (.getRaster image)))]
      (dotimes [index (* width height)]
        (let [offset (* index 4)
              first-channel (bit-and 255 (aget ^bytes pixels offset))
              green (bit-and 255 (aget ^bytes pixels (+ offset 1)))
              third-channel (bit-and 255 (aget ^bytes pixels (+ offset 2)))
              red (if bgra? third-channel first-channel)
              blue (if bgra? first-channel third-channel)]
          (aset-int target index (bit-or (bit-shift-left red 16)
                                         (bit-shift-left green 8) blue))))
      (when-not (javax.imageio.ImageIO/write image "png" (io/file path))
        (throw (ex-info "PNG writer unavailable" {:path path})))
      (assoc (dissoc capture :pixels) :path (str (.getAbsoluteFile (io/file path)))))))

(az/defn- maximize-studio-native! :- :void [[maximize? :bool]]
  (if maximize?
    (glfw/glfwMaximizeWindow studio/studio-window)
    (glfw/glfwRestoreWindow studio/studio-window)))

(defn resize-window-qa!
  "Opt-in, off-render nREPL helper. Returns a render-queue promise; never call
  GLFW directly from the nREPL thread. Restore original bounds after the test."
  [studio? width height]
  (core/on-render! #(resize-window-native! studio? width height)))

(defn maximize-studio-qa!
  "Opt-in, off-render nREPL helper; returns a render-queue promise."
  [maximize?]
  (core/on-render! #(maximize-studio-native! maximize?)))

(deftest window-qa-helpers-queue-native-work
  ;; A missing render queue must not result in a direct Cocoa call.
  (let [queued (atom [])]
    (with-redefs [core/on-render! (fn [f] (swap! queued conj f) :queued)]
      (is (= :queued (resize-window-qa! true 1100 760)))
      (is (= :queued (maximize-studio-qa! true)))
      (is (= 2 (count @queued)))
      (is (every? fn? @queued)))))

(az/defn responsive-layout-contract :- :u32 []
  (let [old-w studio/window-width old-h studio/window-height
        old-top studio/editor-top
        old-routing studio/routing-visible
        old-start studio/timeline-start old-span studio/timeline-seconds
        old-x studio/mouse-x old-y studio/mouse-y
        old-cw scene/canvas-width old-ch scene/canvas-height
        old-vertices scene/vertices old-count scene/vertex-count
        ^{:var [:array 6 mesh/GpuVertex]} vertices (mem/zeroes (az/type [:array 6 mesh/GpuVertex]))]
    (ak/defer (do (set! studio/window-width old-w) (set! studio/window-height old-h)
                 (set! studio/editor-top old-top)
                 (set! studio/routing-visible old-routing)
                 (set! studio/timeline-start old-start) (set! studio/timeline-seconds old-span)
                 (set! studio/mouse-x old-x) (set! studio/mouse-y old-y)
                 (set! scene/canvas-width old-cw) (set! scene/canvas-height old-ch)
                 (set! scene/vertices old-vertices) (set! scene/vertex-count old-count)))
    (set! studio/routing-visible true)
    (set! studio/editor-top 444.0)
    (set! studio/window-width 1100.0) (set! studio/window-height 760.0)
    (when (or (ak/!= (studio/timeline-width) 642.0) (ak/!= (studio/timeline-center) 563.0)
              (ak/!= (studio/right-x 906.0) 906.0) (ak/!= (studio/bottom-y 621.0) 621.0)) (ak/return 1))
    (set! studio/window-width 1400.0) (set! studio/window-height 900.0)
    (when (or (ak/!= (studio/timeline-width) 942.0) (ak/!= (studio/main-width) 1168.0)
              (ak/!= (studio/right-x 906.0) 1206.0) (ak/!= (studio/bottom-y 621.0) 761.0)
              (ak/!= (studio/editor-text-height) 236.0)) (ak/return 2))
    (set! studio/timeline-start 0.0) (set! studio/timeline-seconds 30.0)
    (when (> (ak/abs (- (studio/time-at (studio/timeline-center)) 15.0)) 0.0001) (ak/return 3))
    (when (or (ak/!= (studio/time-at 242.0) 0.0)
              (ak/!= (studio/time-at (studio/right-x 884.0)) 30.0)) (ak/return 4))
    (set! studio/mouse-x 1215.0) (set! studio/mouse-y 160.0)
    (when (or (ak/! (studio/inside? (studio/right-x 906.0) 148.0 170.0 28.0))
              (studio/inside? 906.0 148.0 170.0 28.0)) (ak/return 5))
    (set! studio/mouse-x 40.0) (set! studio/mouse-y 770.0)
    (when (or (ak/! (studio/inside? 28.0 (studio/bottom-y 621.0) 192.0 28.0))
              (studio/inside? 28.0 621.0 192.0 28.0)) (ak/return 6))
    (set! scene/canvas-width 1400.0) (set! scene/canvas-height 900.0)
    (set! scene/vertices (ak/& (az/index vertices 0))) (set! scene/vertex-count 0)
    (scene/rect! 20.0 20.0 100.0 30.0 0xffffff 0.0)
    (let [w (* 700.0 (- (az/field (az/index vertices 1) x) (az/field (az/index vertices 0) x)))
          h (* 450.0 (- (az/field (az/index vertices 2) y) (az/field (az/index vertices 0) y)))]
      (when (or (> (ak/abs (- w 100.0)) 0.001) (> (ak/abs (- h 30.0)) 0.001)) (ak/return 7))))
  0)

(deftest responsive-layout-and-pointer-contract
  (is (= 0 (responsive-layout-contract)) "Fixed point-size primitives and shared layout/hit coordinates"))

(az/defn routing-collapse-contract :- :u32 []
  (let [old-w studio/window-width old-visible studio/routing-visible
        old-menu studio/route-menu old-click studio/clicked old-route-click studio/route-click
        old-drag studio/trim-drag old-start studio/timeline-start old-span studio/timeline-seconds
        old-follow studio/follow-playhead
        old-x studio/mouse-x old-y studio/mouse-y
        mic studio/microphone send studio/effects-output return-device studio/return-input
        headphones studio/headphones monitoring studio/monitor-enabled
        selected studio/selected phase studio/capture-phase seek studio/seek-seconds]
    (ak/defer (do (set! studio/window-width old-w) (set! studio/routing-visible old-visible)
                 (set! studio/route-menu old-menu) (set! studio/clicked old-click)
                 (set! studio/route-click old-route-click) (set! studio/trim-drag old-drag)
                 (set! studio/timeline-start old-start) (set! studio/timeline-seconds old-span)
                 (set! studio/follow-playhead old-follow)
                 (set! studio/mouse-x old-x) (set! studio/mouse-y old-y)))
    (set! studio/window-width 1100.0) (set! studio/routing-visible true)
    (set! studio/mouse-x 1050.0) (set! studio/mouse-y 200.0)
    (when (studio/inside? 16.0 143.0 (studio/main-width) 299.0) (ak/return 1))
    (set! studio/route-menu 2) (set! studio/clicked true) (set! studio/route-click true)
    (set! studio/trim-drag 1)
    (studio/show-routing! false)
    (when (or studio/routing-visible (ak/!= studio/route-menu 0) studio/clicked studio/route-click
              (ak/!= studio/trim-drag 0)) (ak/return 2))
    (when (or (ak/!= (studio/timeline-width) 844.0) (ak/!= (studio/content-x 884.0) 1086.0)
              (ak/!= (studio/right-x 896.0) 896.0)
              (ak/! (studio/inside? 16.0 143.0 (studio/main-width) 299.0))) (ak/return 3))
    (set! studio/window-width 1400.0)
    (when (or (ak/!= (studio/timeline-width) 1144.0) (ak/!= (studio/content-x 884.0) 1386.0)) (ak/return 8))
    (set! studio/window-width 1100.0)
    (set! studio/timeline-start 0.0) (set! studio/timeline-seconds 30.0)
    (when (> (ak/abs (- (studio/time-at (studio/timeline-center)) 15.0)) 0.0001) (ak/return 4))
    (studio/zoom-at! 0.5 (studio/timeline-center))
    (when (> (ak/abs (- (studio/time-at (studio/timeline-center)) 15.0)) 0.0001) (ak/return 5))
    (studio/show-routing! true)
    (when (ak/!= (studio/timeline-width) 642.0) (ak/return 6))
    (when (or (ak/!= studio/microphone mic) (ak/!= studio/effects-output send)
              (ak/!= studio/return-input return-device) (ak/!= studio/headphones headphones)
              (ak/!= studio/monitor-enabled monitoring) (ak/!= studio/selected selected)
              (ak/!= studio/capture-phase phase) (ak/!= studio/seek-seconds seek)) (ak/return 7)))
  0)

(deftest routing-panel-is-layout-only
  (is (= 0 (routing-collapse-contract)) "Collapse reclaims space, dismisses old hits, preserves audio and selection"))

(az/defn editor-divider-contract :- :u32 []
  (let [old-mode studio/workspace-mode old-top studio/editor-top old-height studio/window-height old-width studio/window-width
        old-offset studio/track-offset old-drag studio/divider-drag old-grab studio/divider-grab
        old-click studio/clicked old-double studio/double-clicked old-down studio/mouse-down
        old-x studio/mouse-x old-y studio/mouse-y old-route studio/route-menu
        old-trim studio/trim-drag old-focus studio/name-focus
        selected studio/selected headphones studio/headphones phase studio/capture-phase]
    (ak/defer (do (set! studio/workspace-mode old-mode) (set! studio/editor-top old-top) (set! studio/window-height old-height)
                 (set! studio/window-width old-width) (set! studio/track-offset old-offset)
                 (set! studio/divider-drag old-drag) (set! studio/divider-grab old-grab)
                 (set! studio/clicked old-click) (set! studio/double-clicked old-double)
                 (set! studio/mouse-down old-down) (set! studio/mouse-x old-x) (set! studio/mouse-y old-y)
                 (set! studio/route-menu old-route) (set! studio/trim-drag old-trim) (set! studio/name-focus old-focus)))
    (set! studio/workspace-mode 0)
    (set! studio/window-height 760.0) (set! studio/window-width 1100.0)
    (studio/set-editor-top! 444.0)
    (when (or (ak/!= (studio/visible-row-count) 4) (ak/!= (studio/track-height) 262.0)
              (ak/!= (studio/editor-text-height) 96.0)) (ak/return 1))
    (studio/set-editor-top! -1000.0)
    (when (or (ak/!= studio/editor-top 316.0) (ak/!= (studio/visible-row-count) 2)) (ak/return 2))
    (studio/set-editor-top! 10000.0)
    (when (or (ak/!= studio/editor-top 460.0) (< (studio/editor-text-height) 80.0)) (ak/return 3))
    (set! studio/window-height 900.0) (studio/set-editor-top! 444.0)
    (set! studio/route-menu 0) (set! studio/double-clicked false) (set! studio/mouse-down true)
    (set! studio/clicked true) (set! studio/mouse-x 300.0) (set! studio/mouse-y 445.0)
    (studio/divider-input!)
    (when (or (ak/! studio/divider-drag) studio/clicked (ak/!= studio/editor-top 444.0)) (ak/return 4))
    (set! studio/mouse-y 601.0) (studio/divider-input!)
    (when (or (ak/!= studio/editor-top 600.0) (ak/!= (studio/visible-row-count) 7)
              (ak/!= (studio/editor-y 489.0) 645.0)) (ak/return 5))
    (set! studio/mouse-down false) (studio/divider-input!)
    (when studio/divider-drag (ak/return 6))
    (set! studio/clicked true) (set! studio/double-clicked true) (studio/divider-input!)
    (when (or (ak/!= studio/editor-top 444.0) studio/divider-drag) (ak/return 7))
    (set! studio/route-menu 2) (set! studio/clicked true) (set! studio/mouse-y 444.0)
    (studio/divider-input!)
    (when (or studio/divider-drag (ak/!= studio/editor-top 444.0)) (ak/return 8))
    (set! studio/window-height 4000.0) (studio/set-editor-top! 9999.0)
    (when (ak/!= (studio/visible-row-count) 32) (ak/return 9))
    (when (or (ak/!= studio/selected selected) (ak/!= studio/headphones headphones)
              (ak/!= studio/capture-phase phase)) (ak/return 10)))
  0)

(az/defn clip-viewport-buffer-contract :- :u32 []
  (let [old (az/index studio/clip-viewport 31)]
    (ak/defer (set! (az/index studio/clip-viewport 31) old))
    (studio/clip! 31 2.5 7) (studio/clip-start! 31 3.0) (studio/clip-bin! 31 127 0.75)
    (studio/clip! 32 99.0 99) (studio/clip-bin! 31 128 99.0)
    (let [row (az/index studio/clip-viewport 31)]
      (when (or (ak/!= (az/field row seconds) 2.5) (ak/!= (az/field row start) 3.0)
                (ak/!= (az/field row count) 7) (ak/!= (az/index (az/field row wave) 127) 0.75)) (ak/return 1))))
  0)

(deftest editor-divider-and-viewport-bounds
  (is (= 0 (editor-divider-contract)) "Drag offset, release, reset, limits and popup exclusion")
  (is (= 0 (clip-viewport-buffer-contract)) "32-row backing buffer rejects out-of-range slots/bins"))

(defn live-editor-viewport-qa!
  "Opt-in live viewport/caching test; changes only window/view state and restores it.
  Not a substitute for physical divider dragging or fresh visual inspection."
  []
  (let [render (fn [f] (let [r (deref (core/on-render! f) 10000 ::timeout)]
                        (when (= r ::timeout) (throw (ex-info "Viewport QA render timeout" {})))
                        (when (:error r) (throw (:error r))) (:value r)))
        snapshot #(render (fn [] {:top (az/value studio/editor-top) :rows (studio/visible-row-count)
                                  :offset (az/value studio/track-offset) :loaded-offset (az/value studio/track-snapshot)
                                  :loaded (az/value studio/track-snapshot-count) :uploads (az/value studio/clip-upload-revision)
                                  :selected (az/value studio/selected) :frames (az/value studio/rendered-frames)}))
        before (snapshot)
        bounds (render #(let [b (studio/window-bounds)] (try (az/value b) (finally (az/close! b)))))
        await-rows (fn [rows]
                     (let [deadline (+ (System/nanoTime) 10000000000)]
                       (loop []
                         (let [s (snapshot)]
                           (cond (and (= rows (:rows s) (:loaded s)) (zero? (:offset s)) (zero? (:loaded-offset s))) s
                                 (> (System/nanoTime) deadline) (throw (ex-info "Viewport did not load" s))
                                 :else (do (Thread/sleep 20) (recur)))))))]
    (when-not (render #(and (az/value studio/attached) (>= (az/value scene/passage-entity-count) 9)))
      (throw (ex-info "Viewport QA requires an open studio and at least nine passages" {})))
    (try
      (render #(do (studio/apply-window-bounds! 100 100 1300 900) (studio/update-layout!)
                   (studio/set-editor-top! 600.0) (az/set-value! studio/track-offset 0)))
      (let [expanded (await-rows 9)]
        (Thread/sleep 650)
        (let [stable (snapshot)]
          (assert (= (:uploads expanded) (:uploads stable)) "Unchanged waveforms must not be uploaded on meter ticks")
          (assert (> (:frames stable) (:frames expanded)))
          (render #(studio/set-editor-top! 316.0))
          (let [compact (await-rows 3)]
            (assert (> (:uploads compact) (:uploads expanded)))
            (assert (= (:selected before) (:selected expanded) (:selected compact)))
            {:expanded expanded :stable stable :compact compact})))
      (finally
        (render #(do (studio/apply-window-bounds! (:x bounds) (:y bounds) (:width bounds) (:height bounds))
                     (studio/update-layout!) (studio/set-editor-top! (:top before))
                     (az/set-value! studio/track-offset (:offset before))))
        (studio/save-window! true)))))

(defn live-window-playback-qa!
  "Opt-in live saved-take playback/resize acceptance. Preserves the user's
  selection, positioned cursor, panel split and window bounds; never records or
  publishes. Requires idle take transport with Record disabled. Runs off-render."
  []
  (let [render (fn [f] (let [r (deref (core/on-render! f) 10000 ::timeout)]
                        (when (= r ::timeout) (throw (ex-info "Resize QA render timeout" {})))
                        (when (:error r) (throw (:error r))) (:value r)))
        native-map (fn [value] (try (az/value value) (finally (az/close! value))))
        snapshot #(render (fn [] {:cursor (studio/cursor-seconds)
                                  :playing? (studio/playing-preview?)
                                  :paused (az/value studio/preview-paused)
                                  :selected (az/value studio/selected)
                                  :headphones (az/value studio/headphones)
                                  :signal (studio/playback-signal-count)
                                  :game-frames (az/value scene/rendered-frames)
                                  :renderer (native-map (studio-renderer-snapshot))
                                  :resizes (studio-resize-count)
                                  :top (az/value studio/editor-top)
                                  :offset (az/value studio/track-offset)
                                  :bounds (native-map (studio/window-bounds))}))
        before (snapshot)
        project-before @studio/project]
    (when-not (render #(and (az/value studio/attached) (not (studio/busy?))
                           (not (az/value studio/mix-mode))
                           (zero? (az/value studio/record-enabled))
                           (> (az/value studio/take-seconds) 3.0)))
      (throw (ex-info "Resize QA requires an open idle studio, Record off and a take longer than three seconds" {})))
    (when (or (:playing? before) (not= 1 (get-in before [:bounds :normal])))
      (throw (ex-info "Resize QA will not interrupt playback or a maximized/minimized window" before)))
    (try
      (render #(studio/position! 0.0))
      (studio/preview!)
      (Thread/sleep 100)
      (let [start (snapshot)
            samples (mapv (fn [[width height]]
                            (render #(resize-window-native! true width height))
                            (Thread/sleep 120)
                            (snapshot))
                          [[1100 760] [1280 820] [1160 790] [1283 844]])
            end (last samples)]
        (assert (every? :playing? samples) "Take stays playing throughout swapchain recreation")
        (assert (apply < (map :cursor (cons start samples))) "Real PCM clock advances at every size")
        (assert (> (:signal end) (:signal start)) "Output callback receives non-silent audio")
        (assert (apply < (map #(get-in % [:renderer :frames]) (cons start samples))) "Studio presents at every size")
        (assert (apply < (map :game-frames (cons start samples))) "Independent game renderer keeps advancing")
        (assert (> (:resizes end) (:resizes start)) "Swapchains actually changed")
        (assert (every? #(= [(:selected before) (:headphones before)] [(:selected %) (:headphones %)]) samples))
        (assert (= project-before @studio/project) "View/playback QA does not edit any recordings or project data")
        {:before before :start start :samples samples})
      (finally
        (render #(do (studio/pause-preview!) (studio/position! (:cursor before))
                     (az/set-value! studio/preview-paused (:paused before))
                     (let [{:keys [x y width height]} (:bounds before)]
                       (studio/apply-window-bounds! x y width height))
                     (studio/update-layout!) (studio/set-editor-top! (:top before))
                     (az/set-value! studio/track-offset (:offset before))))
        (studio/save-window! true)))))

(defn- audition-qa-render! [f]
  (let [result (deref (core/on-render! f) 10000 ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info "Audition QA render queue timed out" {})))
    (when (:error result)
      (throw (:error result)))
    (:value result)))

(defn live-output-stop-qa!
  "Opt-in: interrupt Studio's own listening device, inspect pause, retry via
  the native Play button. Existing take/project remain unchanged; no capture."
  []
  (let [render audition-qa-render!
        before (studio/query)
        fields [studio/seek-seconds studio/preview-node studio/preview-paused
                studio/workspace-mode studio/record-enabled studio/workspace-record-prior]
        values (render #(mapv az/value fields))
        ready? (render #(az/value studio/playback-ready))
        state #(render (fn [] {:cursor (studio/cursor-seconds)
                               :paused? (az/value studio/preview-paused)
                               :playing? (studio/playing-preview?)
                               :interrupted? (az/value studio/playback-interrupted)
                               :signal (studio/playback-signal-count)}))
        await-state (fn [predicate]
                      (let [deadline (+ (System/nanoTime) 5000000000)]
                        (loop []
                          (let [value (state)]
                            (cond
                              (predicate value) value
                              (> (System/nanoTime) deadline)
                              (throw (ex-info "Output-loss QA timed out" value))
                              :else (do (Thread/sleep 20) (recur)))))))]
    (assert (and (nil? @studio/session) (nil? @studio/armed)
                  (render #(and (not (studio/busy?)) (not (az/value studio/voice-ready))))))
    (try
      (render #(do
                 (studio/select-workspace! 1)
                 (az/set-value! studio/seek-seconds 0.0)
                 (studio/click-at! 75.0 65.0)))
      (let [playing (await-state #(and (:playing? %) (> (:cursor %) 0.3) (> (:signal %) 500)))]
        (assert (render #(interrupt-listening-output-qa! false)))
        (let [paused (await-state #(and (:paused? %) (:interrupted? %) (not (:playing? %))))]
          (Thread/sleep 250)
          (let [held (state)]
            (assert (= (:cursor paused) (:cursor held)) "Stopped output must not advance the take")
            (assert (= (:signal paused) (:signal held))))
          (capture-window-qa! true "build/recording-qa/studio-output-stopped.png")
          (render #(studio/click-at! 75.0 65.0))
          (let [resumed (await-state #(and (:playing? %) (not (:interrupted? %))
                                            (> (:cursor %) (+ (:cursor paused) 0.1))
                                            (> (:signal %) (:signal paused))))]
            (assert (= (get-in before [:project :revision]) (:revision @studio/project)))
            {:playing playing :paused paused :resumed resumed
             :revision (:revision @studio/project)})))
      (finally
        (studio/command! {:op :transport/stop})
        (render #(do
                   (when-not ready? (studio/close-playback!))
                   (doseq [[field value] (map vector fields values)]
                     (az/set-value! field value))))))))

(defn live-mix-output-stop-qa!
  "Opt-in idle-app QA with an existing take and an otherwise empty mixer.
  Stop only our output, resume through Record-mode Play, restore the empty mix."
  []
  (let [render audition-qa-render!
        before (studio/query)
        id (#'studio/focused-id)
        path (get-in @studio/takes [id :selected])
        fields [studio/seek-seconds studio/preview-node studio/preview-paused
                studio/workspace-mode studio/record-enabled studio/workspace-record-prior
                studio/mix-mode mixer/output-index]
        values (render #(mapv az/value fields))
        state #(render (fn [] {:cursor (mixer/cursor-frame)
                               :opened? (az/value mixer/opened)
                               :mix? (az/value studio/mix-mode)
                               :paused? (az/value studio/preview-paused)
                               :playing? (mixer/playing?)}))
        await-state (fn [predicate]
                      (let [deadline (+ (System/nanoTime) 5000000000)]
                        (loop []
                          (let [value (state)]
                            (cond
                              (predicate value) value
                              (> (System/nanoTime) deadline)
                              (throw (ex-info "Mix output-loss QA timed out" value))
                              :else (do (Thread/sleep 20) (recur)))))))]
    (assert (and path (nil? @studio/session) (nil? @studio/armed)
                 (render #(and (not (studio/busy?))
                                (not (az/value studio/voice-ready))
                                (not (az/value mixer/opened))
                                (zero? (az/value mixer/clip-count))))))
    (try
      (render #(do
                 (studio/select-workspace! 1)
                 (mixer/reset!)
                 (assert (mixer/add-file! path))
                 (mixer/configure-clip! 0 0 0.5 0.0 240 false false)
                 (az/set-value! studio/mix-mode true)
                 (studio/click-at! 75.0 65.0)))
      (let [playing (await-state #(and (:mix? %) (:playing? %) (> (:cursor %) 14400)))]
        (assert (render #(interrupt-listening-output-qa! true)))
        (let [paused (await-state #(and (:mix? %) (:paused? %)
                                        (not (:opened? %)) (not (:playing? %))))]
          (Thread/sleep 250)
          (assert (= (:cursor paused) (:cursor (state))))
          (capture-window-qa! true "build/recording-qa/studio-mix-output-stopped.png")
          (render #(studio/click-at! 75.0 65.0))
          (let [resumed (await-state #(and (:mix? %) (:opened? %) (:playing? %)
                                           (> (:cursor %) (+ (:cursor paused) 4800))))]
            (assert (= (get-in before [:project :revision]) (:revision @studio/project)))
            {:playing playing :paused paused :resumed resumed
             :revision (:revision @studio/project)})))
      (finally
        (studio/command! {:op :transport/stop})
        (render #(do
                   (mixer/reset!)
                   (doseq [[field value] (map vector fields values)]
                     (az/set-value! field value))))))))

(defn- audition-qa-state []
  (audition-qa-render!
    #(hash-map :selected (az/value studio/selected)
               :mode (az/value studio/workspace-mode)
               :playing? (studio/playing-preview?)
               :paused? (az/value studio/preview-paused)
               :busy? (studio/busy?)
               :voice-ready? (az/value studio/voice-ready)
               :duration (az/value studio/take-seconds)
               :seek (az/value studio/seek-seconds)
               :cursor (studio/cursor-seconds)
               :signal (studio/playback-signal-count)
               :game-frames (az/value scene/rendered-frames)
               :studio-frames (az/value studio/rendered-frames)
               :record-enabled (az/value studio/record-enabled)
               :capture-phase (studio/capture-phase-value))))

(defn- await-audition-state! [description predicate]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (let [state (audition-qa-state)]
        (cond
          (predicate state) state
          (> (System/nanoTime) deadline)
          (throw (ex-info (str "Audition QA: " description) state))
          :else
          (do
            (Thread/sleep 10)
            (recur)))))))

(defn- run-audition-sequence! [index button-source]
  (let [stages (atom {})
        click! (fn [x y] (audition-qa-render! #(studio/click-at! x y)))
        audition! (fn []
                    (case button-source
                      :edit-row
                      (click! 211.0 (audition-qa-render!
                                     #(double (+ 183.0 (* 56.0 (- index (az/value studio/track-offset)))))))
                      :takes-card
                      (let [id (audition-qa-render! #(#'studio/native-string (studio/node-id index)))
                            slot (first (keep-indexed (fn [slot card]
                                                       (when (and (= id (:id card)) (:chosen? card)) slot))
                                                     (:cards @studio/take-grid-cache)))]
                        (assert (some? slot) "Selected take must have a matching grid card")
                        (click! (audition-qa-render! #(+ (studio/take-card-x (mod slot 3)) 20.0))
                                (+ 186.0 (* 62.0 (quot slot 3)))))
                      :record-button
                      (click! 325.0 (audition-qa-render! #(studio/bottom-y 699.0)))))
        step! (fn [stage action predicate]
                (action)
                (let [state (await-audition-state! (str "failed at " stage) predicate)]
                  (swap! stages assoc stage state)
                  state))
        loaded (step! :loaded #(click! 100.0 180.0)
                      #(and (= index (:selected %)) (> (:duration %) 3.0)))
        cue (* 0.25 (:duration loaded))]
    (step! :positioned
           #(audition-qa-render!
              (fn [] (studio/click-at! (+ 242.0 (* 0.25 (studio/timeline-width)))
                                      (studio/bottom-y 643.0))))
           #(< (abs (- cue (:seek %))) 0.001))
    (when (= button-source :edit-row)
      (audition-qa-render!
        #(do
           (studio/set-workspace-mode! false)
           (az/set-value! studio/page 0)
           (az/set-value! studio/record-enabled 1)))
      (await-audition-state!
        "Edit-row waveforms did not load"
        (fn [_]
          (audition-qa-render!
            #(and (= (az/value studio/track-snapshot) (az/value studio/track-offset))
                  (pos? (az/value studio/track-snapshot-count)))))))
    (when (= button-source :takes-card)
      (audition-qa-render!
        #(do
           (studio/select-workspace! 2)
           (az/set-value! studio/record-enabled 1)))
      (await-audition-state!
        "Takes grid did not load"
        (fn [_]
          (and (= index (first (:key @studio/take-grid-cache)))
               (some #(and (:chosen? %) (:available? %)) (:cards @studio/take-grid-cache))
               (audition-qa-render!
                 #(= (az/value studio/take-grid-offset) (az/value studio/track-offset)))))))
    (step! :started audition! #(and (:playing? %) (> (:cursor %) cue)))
    (Thread/sleep 120)
    (step! :paused audition! #(and (:paused? %) (not (:playing? %))))
    (Thread/sleep 180)
    (swap! stages assoc :held (audition-qa-state))
    (step! :resumed audition!
           #(and (:playing? %) (> (:cursor %) (get-in @stages [:held :cursor]))))
    (step! :edit #(click! 279.0 22.0) #(and (= 0 (:mode %)) (:playing? %)))
    (step! :record #(click! 350.0 22.0) #(and (= 1 (:mode %)) (:playing? %)))
    (step! :stopped #(click! 192.0 65.0)
           #(and (not (:voice-ready? %)) (zero? (:cursor %)) (not (:busy? %))))
    {:cue cue :stages @stages}))

(defn- verify-audition-sequence! [{:keys [cue stages]} project-before]
  (let [{:keys [started paused held record]} stages]
    (assert (< (abs (- (:seek started) cue)) 0.001)
            "Clicking audition on the selected passage must retain its positioned cue")
    (assert (< (- (:cursor started) cue) 0.5) "Audition starts near the chosen cue")
    (assert (< (abs (- (:cursor paused) (:cursor held))) (/ 1.0 48000.0))
            "Paused audio position must not advance")
    (assert (> (:signal record) (:signal started)) "Real non-silent PCM reached output")
    (assert (> (:studio-frames record) (:studio-frames started)))
    (assert (> (:game-frames record) (:game-frames started)))
    (assert (every? #(zero? (:capture-phase %)) (vals stages)))
    (assert (and (nil? @studio/session) (nil? @studio/armed))
            "Audition must not record even with global REC enabled")
    (assert (= project-before @studio/project) "Real takes must remain unchanged")))

(defn live-audition-qa!
  "Opt-in real saved-audio test through native hit testing, NOT OS mouse QA.
  Call off-render with a voiced passage index whose selected take is >3 seconds.
  Optional source is :record-button (default), :edit-row or :takes-card.
  Requires stopped/unloaded playback. Never records, publishes or edits takes."
  ([index]
   (live-audition-qa! index :record-button))
  ([index button-source]
   (assert (#{:record-button :edit-row :takes-card} button-source) "Unknown audition control")
   (let [fields [studio/selected studio/workspace-mode studio/workspace-record-prior
                 studio/record-enabled studio/record-track studio/track-offset
                 studio/seek-seconds studio/preview-node studio/preview-paused
                 studio/record-scroll studio/focus-scroll studio/route-menu studio/page]
         before (audition-qa-state)
         values (audition-qa-render! #(mapv az/value fields))
         engine-ready? (audition-qa-render! #(az/value studio/playback-ready))
         project-before @studio/project]
     (assert (and (not (:playing? before))
                  (not (:voice-ready? before))
                  (not (:busy? before))
                  (audition-qa-render! #(not (az/value studio/mix-mode)))
                  (nil? @studio/session)
                  (nil? @studio/armed))
             "Audition QA must not interrupt an existing sound or capture")
     (try
       (audition-qa-render!
         #(do
            (studio/set-workspace-mode! true)
            (az/set-value! studio/record-enabled 1)
            (az/set-value! studio/track-offset index)))
       (let [result (run-audition-sequence! index button-source)]
         (try
           (verify-audition-sequence! result project-before)
           (catch AssertionError error
             (throw (ex-info (ex-message error) result error))))
         result)
       (finally
         (audition-qa-render!
           #(do
              (studio/stop-voice!)
              (when-not engine-ready?
                (studio/close-playback!))
              (doseq [[field value] (map vector fields values)]
                (az/set-value! field value)))))))))

(defn restore-qa-recording-pointers!
  "Opt-in QA cleanup: retain new labelled history/WAVs, but restore the pre-test
  default dry/wet sources. Exact QA paths and revision guards prevent replacing
  an intervening user edit. Does not change the selected passage/take or publish."
  [id before qa-paths]
  (let [op (keyword "qa" (str "restore-pointers-" (java.util.UUID/randomUUID)))
        allowed (set qa-paths)]
    (studio/register-command! op
      {:description "Restore pre-QA dry/wet references; retain labelled QA takes."
       :validate empty?
       :handler
       (fn [_]
         (#'studio/change-takes! "Restore pre-QA recording pointers"
           (fn [takes]
             (let [current (get takes id)]
               (doseq [k [:dry :wet]]
                 (when-not (or (= (get current k) (get before k)) (contains? allowed (get current k)))
                   (throw (ex-info "QA cleanup will not replace a changed user reference" {:id id :field k}))))
               (update takes id
                 (fn [entry]
                   (reduce (fn [e k] (if (contains? before k) (assoc e k (get before k)) (dissoc e k)))
                           entry [:dry :wet]))))))
         {:restored true})})
    (try
      (studio/command! {:op op :args {} :expected-revision (:revision @studio/project)})
      (finally (studio/unregister-command! op)))))

(defn live-routing-playback-qa!
  "Opt-in live acceptance, NOT part of the automatic suite. Requires an open idle
  studio at take position zero. Uses the real native button hit path and restores
  the original panel visibility. Call from a REPL/client, never the render thread."
  []
  (let [render (fn [f] (let [r (deref (core/on-render! f) 10000 ::timeout)]
                        (when (= r ::timeout) (throw (ex-info "QA render timeout" {})))
                        (when (:error r) (throw (:error r))) (:value r)))
        snapshot #(render (fn [] {:visible (az/value studio/routing-visible)
                                  :width (studio/timeline-width) :playing? (studio/playing-preview?)
                                  :cursor (studio/cursor-seconds) :signal (studio/playback-signal-count)
                                  :headphones (az/value studio/headphones) :selected (az/value studio/selected)
                                  :frames (az/value studio/rendered-frames)}))
        command (fn [op args]
                  (let [ticket (studio/submit! {:op op :args args}) deadline (+ (System/nanoTime) 10000000000)]
                    (loop []
                      (let [r (studio/result (:request-id ticket))]
                        (cond (= :done (:status r)) r
                              (= :error (:status r)) (throw (ex-info "QA command rejected" r))
                              (> (System/nanoTime) deadline) (throw (ex-info "QA command timeout" r))
                              :else (do (Thread/sleep 10) (recur)))))))
        before (snapshot)]
    (when-not (render #(and (az/value studio/attached) (not (studio/busy?))
                           (not (az/value studio/mix-mode)) (not (az/value studio/preview-paused))
                           (> (az/value studio/take-seconds) 1.0)))
      (throw (ex-info "QA requires an open idle studio with a take longer than one second" {})))
    (when (or (:playing? before) (not (zero? (:cursor before))))
      (throw (ex-info "QA will not interrupt existing playback or a positioned take" before)))
    (try
      (render #(studio/show-routing! true))
      (command :transport/play {})
      (Thread/sleep 100)
      (let [start (snapshot)
            samples (mapv (fn [visible]
                            (render #(studio/click-at! (+ (studio/right-x 896.0) 40.0) 116.0))
                            (Thread/sleep 150)
                            (let [s (snapshot)]
                              (assert (= visible (:visible s)) "Native routing button must consume the click")
                              s)) [false true false true])
            end (last samples)]
        (assert (every? :playing? samples) "Playback stays active through layout changes")
        (assert (> (- (:cursor end) (:cursor start)) 0.5) "Audio clock advances, not just UI frames")
        (assert (> (:signal end) (:signal start)) "Output callback receives non-silent PCM")
        (assert (every? #(= [(:headphones before) (:selected before)]
                           [(:headphones %) (:selected %)]) samples))
        {:start start :samples samples})
      (finally
        (command :transport/stop {})
        (render #(studio/show-routing! (:visible before)))
        (studio/save-window! true)))))

(az/defn game-voice-cursor :- :u64 []
  (let [^{:var :u64} cursor 0]
    (when scene/voice-ready
      (set! _ (audio/ma_sound_get_cursor_in_pcm_frames
                (ak/& (az/index scene/voices scene/voice-slot)) (ak/& cursor))))
    cursor))

(az/defn game-voice-playing? :- :bool []
  (and scene/voice-ready
       (ak/!= (audio/ma_sound_is_playing
                (ak/& (az/index scene/voices scene/voice-slot))) 0)))

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

(az/defn held-scroll-modifiers :- :u32 []
  (when (ak/== studio/studio-window ak/null) (ak/return 0))
  (| (if (or (ak/== (glfw/glfwGetKey studio/studio-window glfw/GLFW_KEY_LEFT_ALT) glfw/GLFW_PRESS)
             (ak/== (glfw/glfwGetKey studio/studio-window glfw/GLFW_KEY_RIGHT_ALT) glfw/GLFW_PRESS))
       (ak/as :u32 4) (ak/as :u32 0))
     (if (or (ak/== (glfw/glfwGetKey studio/studio-window glfw/GLFW_KEY_LEFT_SHIFT) glfw/GLFW_PRESS)
             (ak/== (glfw/glfwGetKey studio/studio-window glfw/GLFW_KEY_RIGHT_SHIFT) glfw/GLFW_PRESS))
       (ak/as :u32 1) (ak/as :u32 0))))

(az/defn game-suppressed? :- :bool [] scene/studio-audio-suppressed)
(az/defn game-muted? :- :bool [] scene/audio-muted)
(az/defn set-game-muted! :- :void [[muted :bool]] (set! scene/audio-muted muted))
(az/defconst volume-api (ak/cImport (ak/cInclude "miniaudio.h")))

;; Opt-in virtual audio source for real device/capture tests. Never uses a
;; physical microphone, speaker, system-default device, or the game audio graph.
(az/defconst TestToneDevice (az/field volume-api ma_device))
(az/defvar test-tone-device TestToneDevice ak/undefined)
(az/defvar test-tone-running :bool false)
(az/defvar test-tone-frame :u64 0)
(az/defvar test-tone-offset :u32 0)

(az/defn test-tone-callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[device [:c-pointer TestToneDevice]]
   [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]]
   [frames :u32]]
  (set! _ device)
  (set! _ input)
  (when (ak/== output ak/null)
    (ak/return))
  (let [^{:zig/type [:c-pointer :f32]} samples (ak/ptrCast (ak/alignCast output))]
    (dotimes [i frames]
      (dotimes [channel 16]
        (set! (az/index samples (+ (* i 16) channel)) 0.0))
      (let [time (/ (ak/as :f64 (ak/floatFromInt test-tone-frame)) 48000.0)]
        (set! (az/index samples (+ (* i 16) test-tone-offset))
              (ak/floatCast (* 0.08 (ak/sin (* 1382.300768 time)))))
        (set! (az/index samples (+ (* i 16) test-tone-offset 1))
              (ak/floatCast (* 0.06 (ak/sin (* 2073.451151 time))))))
      (set! test-tone-frame (+ test-tone-frame 1)))))

(az/defn stop-test-tone! :- :void []
  (when test-tone-running
    ((az/field volume-api ma_device_uninit) (ak/& test-tone-device))
    (set! test-tone-running false)))

(az/defn start-test-tone! :- :bool [[output-index :u32] [offset :u32]]
  (when (or test-tone-running
            (ak/! recorder/initialized)
            (>= output-index recorder/playback-count)
            (and (ak/!= offset 0) (ak/!= offset 4))
            (ak/! (mem/eql :u8 (recorder/device-name false output-index) "BlackHole 16ch")))
    (ak/return false))
  (let [^:var config ((az/field volume-api ma_device_config_init)
                      (az/field volume-api ma_device_type_playback))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config playback) pDeviceID)
          (ak/ptrCast (ak/& (az/field (az/index recorder/playback-info output-index) id))))
    (set! (az/field (az/field config playback) format) (az/field volume-api ma_format_f32))
    (set! (az/field (az/field config playback) channels) 16)
    (set! (az/field config dataCallback) (ak/& test-tone-callback))
    (set! test-tone-offset offset)
    (set! test-tone-frame 0)
    (when (ak/!= ((az/field volume-api ma_device_init)
                  (ak/ptrCast (ak/& recorder/context)) (ak/& config) (ak/& test-tone-device)) 0)
      (ak/return false))
    (when (ak/!= ((az/field volume-api ma_device_start) (ak/& test-tone-device)) 0)
      ((az/field volume-api ma_device_uninit) (ak/& test-tone-device))
      (ak/return false)))
  (set! test-tone-running true)
  true)

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

(deftest recording-level-guidance
  (is (= -120.0 (studio/peak-dbfs 0.0)))
  (is (= -120.0 (studio/peak-dbfs Double/NaN)))
  (is (< (abs (+ 20.0 (studio/peak-dbfs 0.1))) 0.00001))
  (doseq [[input returned expected] [[0.2 nil :ok] [0.2 0.1 :ok]
                                     [0.0 0.0 :silent-input] [0.1 0.0 :silent-return]
                                     [0.005 0.000332 :low-level] [1.0 0.1 :clipping]
                                     [0.1 1.0 :clipping]]]
    (let [health (studio/recording-health input returned)]
      (is (= expected (:kind health)))
      (is (= (= expected :ok) (nil? (:message health)))))))

(deftest routing-signal-evidence-is-current-and-bounded
  (let [state #(studio/routing-signal-state
                 :return
                 (merge {:active? false
                         :bypassed? false
                         :phase 0
                         :current-peak 0.0
                         :held-peak 0.8}
                        %))]
    (is (= :not-checked (:state (state {})))
        "A held peak from an old take cannot verify an idle device")
    (is (nil? (:peak-dbfs (state {}))))
    (is (= "FX: idle / not checked" (:label (state {}))))
    (is (= :bypassed (:state (state {:bypassed? true}))))
    (is (= :count-in (:state (state {:phase 1 :counting-in? true}))))
    (is (= :preparing (:state (state {:phase 1 :counting-in? false}))))
    (is (= "FX: opening audio devices"
           (:label (state {:phase 1 :counting-in? false}))))
    (is (nil? (:peak-dbfs (state {:phase 1 :counting-in? false}))))
    (is (= :bypassed (:state (state {:phase 1 :bypassed? true})))
        "Dry capture must not imply that the FX return is being opened")
    (is (= :listening (:state (state {:active? true}))))
    (is (= "FX: listening / no signal" (:label (state {:active? true}))))
    (is (= "FX: tail / no signal" (:label (state {:active? true :phase 3}))))
    (doseq [invalid [nil Double/NaN Double/POSITIVE_INFINITY -1.0]]
      (is (= :listening (:state (state {:active? true :current-peak invalid})))))
    (let [signal (state {:active? true :current-peak 0.01 :held-peak 0.1})]
      (is (= :signal-present (:state signal)))
      (is (= "FX signal; peak -20 dBFS" (:label signal)))
      (is (= -40.0 (:current-dbfs signal)))
      (is (= false (:effect-verified? signal))
          "Audio on a return is not proof that Bitwig or an effect produced it"))
    (is (= :signal-present
           (:state (state {:active? true :bypassed? true :current-peak 0.1})))
        "Actual monitoring activity takes precedence over an idle mode choice")
    (is (= "Input: idle / not checked"
           (:label (studio/routing-signal-state :input {}))))))

(az/defn set-name-focus-qa! :- :void [[focused? :bool]]
  (set! studio/name-focus focused?))

(deftest take-name-focus-is-a-jvm-boolean
  (let [before (studio/name-focused?)]
    (try
      (doseq [focused? [false true false]]
        (set-name-focus-qa! focused?)
        (is (= focused? (studio/name-focused?)))
        (is (instance? Boolean (studio/name-focused?))
            "Raw [0] bytes are truthy and must not block take-name refresh"))
      (finally
        (set-name-focus-qa! before)))))

(deftest take-name-reset-ends-previous-target-edit
  (let [before-focus (studio/name-focused?)
        before-name (#'studio/native-string (studio/entered-name))]
    (try
      (studio/name! "Unfinished draft")
      (set-name-focus-qa! true)
      (#'studio/reset-take-name! "Next take")
      (is (false? (studio/name-focused?)))
      (is (= "Next take" (#'studio/native-string (studio/entered-name))))
      (#'studio/reset-take-name! "")
      (is (= "" (#'studio/native-string (studio/entered-name))))
      (is (zero? (az/value studio/name-history-end)))
      (finally
        (studio/name! before-name)
        (set-name-focus-qa! before-focus)))))

(deftest recording-guidance-is-english
  (is (= "No input signal: check the selected mic and its gain. Take retained."
         (:message (studio/recording-health 0.0 0.0))))
  (is (= "Input received, but no FX return: check Bitwig input 1/2 and output 3/4."
         (:message (studio/recording-health 0.1 0.0))))
  (is (= "Clipping: lower the mic / FX gain. Take retained."
         (:message (studio/recording-health 1.0 0.1))))
  (is (.contains (:message (studio/recording-health 0.005 0.000332)) "Check mic / Bitwig gain")))

(deftest native-signal-meter-precision
  (when (or @studio/session @studio/armed)
    (throw (ex-info "Run meter fixture only with idle recording" {})))
  (let [fields [recorder/input-peak-ppm recorder/return-peak-ppm
                recorder/input-held-ppm recorder/return-held-ppm]
        values (mapv az/value fields)]
    (try
      (doseq [field fields] (az/set-value! field 0))
      (recorder/update-signal-level! true 0.005265)
      (recorder/update-signal-level! false 0.000332)
      (is (< (abs (- 0.005265 (recorder/signal-peak true true))) 0.000002))
      (is (< (abs (- 0.000332 (recorder/signal-peak false true))) 0.000002))
      (recorder/update-signal-level! true 0.0)
      (is (zero? (recorder/signal-peak true false)))
      (is (pos? (recorder/signal-peak true true)))
      (is (false? (recorder/tail-active?)))
      (finally (doseq [[field value] (map vector fields values)] (az/set-value! field value))))))

(deftest effects-pass-presentation-uses-active-capture
  ;; No audio device changes: native predicates read the captured operation,
  ;; independently of the preference for the next microphone recording.
  (when (or @studio/session @studio/armed)
    (throw (ex-info "Run presentation fixture only while capture is idle" {})))
  (let [fields [studio/capture-phase studio/record-mode recorder/mode]
        before (mapv az/value fields)
        text #(#'studio/native-string (studio/record-route-label))]
    (try
      (doseq [configured [1 8]]
        (az/set-value! studio/record-mode configured)
        (doseq [phase [2 4]]
          (az/set-value! studio/capture-phase phase)
          (doseq [[mode fx? pass? label] [[1 false false "Dry"]
                                         [2 true true "FX pass"]
                                         [3 true false "Live FX"]]]
            (az/set-value! recorder/mode mode)
            (is (= fx? (studio/capture-fx?)))
            (is (= pass? (studio/effects-pass?)))
            (is (= label (text)))))
        (doseq [phase [0 1]]
          (az/set-value! studio/capture-phase phase)
          (is (false? (studio/capture-fx?)))
          (is (false? (studio/effects-pass?)))
          (is (= (if (= configured 8) "Live FX" "Dry") (text)))))
      (is (= "Take send signal; peak -20 dBFS"
             (:label (studio/routing-signal-state
                       :input {:active? true :source :take :phase 2
                               :current-peak 0.1 :held-peak 0.1}))))
      (finally
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))))))

(deftest capture-start-does-not-display-previous-take
  (when (or @studio/session @studio/armed)
    (throw (ex-info "Run presentation fixture only while capture is idle" {})))
  (let [fields [studio/busy studio/capture-phase studio/take-seconds
                studio/wave studio/waveform-owner studio/waveform-owner-length
                studio/waveform-uploaded studio/input-meter-text
                studio/input-meter-length studio/return-meter-text
                studio/return-meter-length studio/alert-text studio/alert-length
                recorder/mode]
        before (mapv az/value fields)]
    (try
      (az/set-value! recorder/mode 2)
      (az/set-value! studio/take-seconds 20.0)
      (dotimes [i 128]
        (studio/set-wave! i 0.5))
      (studio/meter-labels! "Input: idle" "FX: bypassed (Dry mode)")
      (studio/alert! "Previous take was quiet")
      (studio/begin-capture-presentation!)
      (is (= 2 (az/value studio/capture-phase)))
      (is (= 1 (az/value studio/busy)))
      (is (zero? (az/value studio/take-seconds)))
      (is (every? zero? (az/value studio/wave)))
      (is (zero? (az/value studio/alert-length)))
      (is (= "FX: waiting for signal"
             (String. (byte-array (map unchecked-byte
                                  (take (az/value studio/return-meter-length)
                                        (az/value studio/return-meter-text))))
                      "UTF-8")))
      (finally
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))))))

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

(deftest sans-atlas-metrics-and-gutters
  (let [pixels (java.nio.file.Files/readAllBytes (.toPath (io/file "resources/demo/atlas.rgba")))
        metrics (java.nio.file.Files/readAllBytes (.toPath (io/file "resources/demo/ui-glyph-advances.bin")))
        buffer (doto (java.nio.ByteBuffer/wrap metrics) (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (is (= 1024 (alength metrics)))
    (is (every? #(and (Float/isFinite %) (<= 0.0 % 60.0))
                (repeatedly 256 #(.getFloat buffer))))
    (is (every? zero?
          (for [code (range 256) y (range 50) x (range 64)
                :when (or (< x 2) (>= x 62) (< y 2) (>= y 48))]
            (aget pixels (+ 3 (* 4 (+ (+ 1024 (* (mod code 16) 64) x)
                                        (* 2048 (+ 736 (* (quot code 16) 50) y)))))))))))

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
  (let [fields [studio/window-width studio/window-height studio/routing-visible studio/editor-top
                studio/timeline-start studio/timeline-seconds studio/trim-in studio/trim-out studio/trim-drag]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/window-width 1100.0) (az/set-value! studio/window-height 760.0)
      (az/set-value! studio/routing-visible true)
      (az/set-value! studio/editor-top 444.0)
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

(az/defn workspace-presentation-contract :- :u32 []
  (let [mode studio/workspace-mode enabled studio/record-enabled prior studio/workspace-record-prior
        trim studio/trim-drag divider studio/divider-drag
        focus studio/name-focus drag studio/name-drag menu studio/route-menu click studio/clicked
        x studio/mouse-x y studio/mouse-y scroll studio/record-scroll height studio/record-text-height
        offset studio/track-offset track-scroll studio/track-scroll window-h studio/window-height
        count scene/passage-entity-count]
    (ak/defer (do (set! studio/workspace-mode mode) (set! studio/record-enabled enabled)
      (set! studio/workspace-record-prior prior) (set! studio/trim-drag trim)
      (set! studio/divider-drag divider) (set! studio/name-focus focus) (set! studio/name-drag drag)
      (set! studio/route-menu menu) (set! studio/clicked click) (set! studio/mouse-x x)
      (set! studio/mouse-y y) (set! studio/record-scroll scroll) (set! studio/record-text-height height)
      (set! studio/track-offset offset) (set! studio/track-scroll track-scroll)
      (set! studio/window-height window-h) (set! scene/passage-entity-count count)))
    (set! studio/workspace-mode 0) (set! studio/record-enabled 0)
    (set! studio/trim-drag 1) (set! studio/divider-drag true)
    (set! studio/name-focus true) (set! studio/name-drag true)
    (set! studio/route-menu 2) (set! studio/clicked true)
    (studio/set-workspace-mode! true)
    (when (ak/!= studio/workspace-mode 1) (ak/return 1))
    (when (or (ak/!= studio/record-enabled 1) (ak/!= studio/workspace-record-prior 0)) (ak/return 6))
    (when (or (ak/!= studio/trim-drag 0) studio/divider-drag studio/name-focus studio/name-drag
              (ak/!= studio/route-menu 0) studio/clicked) (ak/return 2))
    (set! studio/mouse-x 300.0) (set! studio/mouse-y 210.0)
    (set! studio/record-text-height 2000.0) (set! studio/record-scroll 0.0)
    (studio/scroll-by! 2.0 -2.0 true true)
    (when (ak/!= studio/record-scroll 48.0) (ak/return 3))
    (set! studio/mouse-y 200.0)
    (studio/scroll-by! 0.0 -1.0 false false)
    (when (ak/!= studio/record-scroll 48.0) (ak/return 11))
    (set! studio/mouse-y (+ 203.0 (studio/record-pane-height)))
    (studio/scroll-by! 0.0 -1.0 false false)
    (when (ak/!= studio/record-scroll 72.0) (ak/return 12))
    (set! studio/mouse-x 100.0) (set! studio/window-height 760.0)
    (set! scene/passage-entity-count 20) (set! studio/track-offset 0) (set! studio/track-scroll 0.0)
    (dotimes [_ 4] (studio/scroll-by! 0.0 -0.3 false false))
    (when (ak/!= studio/track-offset 1) (ak/return 9))
    (studio/scroll-by! 0.0 -1000.0 false false)
    (when (ak/!= studio/track-offset 12) (ak/return 10))
    (set! studio/mouse-x 300.0)
    (set! studio/mouse-y (ak/as :f64 (ak/floatCast studio/editor-top)))
    (set! studio/clicked true) (studio/divider-input!)
    (when studio/divider-drag (ak/return 4))
    (set! studio/record-enabled 0)
    (studio/set-workspace-mode! true)
    (when (ak/!= studio/record-enabled 0) (ak/return 7))
    (studio/set-workspace-mode! false)
    (when (or (ak/!= studio/workspace-mode 0) (ak/!= studio/record-enabled 0)) (ak/return 5))
    (set! studio/record-enabled 1)
    (studio/set-workspace-mode! true)
    (set! studio/record-enabled 0)
    (studio/set-workspace-mode! false)
    (when (ak/!= studio/record-enabled 1) (ak/return 8)))
  0)

(deftest workspace-presentation-isolation
  ;; Call on the render thread: no fake device flags, capture or project edits.
  (let [session-fields [studio/selected studio/record-enabled studio/record-track
                        studio/record-mode studio/capture-phase studio/headphones
                        studio/microphone studio/return-input studio/effects-output
                        studio/seek-seconds studio/preview-paused
                        studio/timeline-start studio/timeline-seconds studio/editor-top]
        session-before (mapv az/value session-fields)]
    (is (= 0 (workspace-presentation-contract)) "Mode input is isolated and stale drags are dismissed")
    (is (= session-before (mapv az/value session-fields)))))

(deftest recording-workspace-phase-guidance
  (let [text (fn [phase enabled? armed?]
               (#'studio/native-string (studio/record-guidance phase enabled? armed?)))]
    (is (= "Armed. Play to record." (text 0 true true)))
    (is (= "REC off: enable REC to record." (text 0 false true)))
    (is (= "Select and arm a voiced passage." (text 0 true false)))
    (doseq [enabled? [true false]]
      (is (= "Recording. Stop to save." (text 2 enabled? true)))
      (is (= "Capturing FX tail. Saving shortly." (text 3 enabled? true)))))
  (let [fields [studio/framebuffer-scale studio/window-width studio/routing-visible]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/window-width 1100.0)
      (az/set-value! studio/routing-visible true)
      (doseq [scale [1.0 1.5 2.0]]
        (az/set-value! studio/framebuffer-scale scale)
        (let [xs (mapv #(studio/take-playhead-x % 5.0) [0.0 1.001 1.02 4.99 5.0 99.0])]
          (is (apply <= xs))
          (is (every? #(<= 242.0 % 883.0) xs))
          (is (every? #(< (abs (- (* scale %) (Math/rint (* scale %)))) 0.0001) xs))
          (is (= 242.0 (studio/take-playhead-x 0.0 0.0)))))
      (finally (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

(deftest zero-count-in-shows-device-preparation
  (let [before (az/value studio/countdown-until)]
    (try
      (doseq [[deadline label guidance]
              [[0.0 "PREPARING" "Opening audio devices. Stop to cancel."]
               [1.0e9 "COUNT-IN" "Count-in running. Stop to cancel."]]]
        (az/set-value! studio/countdown-until deadline)
        (is (= (pos? deadline) (studio/counting-in?)))
        (doseq [enabled? [false true]]
          (is (= label (#'studio/native-string
                         (studio/transport-state-label 1 true false false enabled?))))
          (is (= guidance (#'studio/native-string
                            (studio/record-workspace-guidance 1 true false false))))))
      (finally
        (az/set-value! studio/countdown-until before)))))

(deftest recording-and-audition-status-are-distinct
  (let [label (fn [& args]
                (#'studio/native-string (apply studio/transport-state-label args)))
        guidance (fn [& args]
                   (#'studio/native-string (apply studio/record-workspace-guidance args)))]
    (doseq [record-enabled? [false true]]
      (is (= "PLAYING" (label 0 false true false record-enabled?)))
      (is (= "PAUSED" (label 0 false false true record-enabled?)))
      (is (= "WORKING" (label 0 true true false record-enabled?)))
      (is (= "RECORDING" (label 2 true true false record-enabled?)))
      (is (= "FX TAIL" (label 3 true true false record-enabled?)))
      (is (= "AUDIO STOPPED" (label 4 true true false record-enabled?))))
    (is (= "Audio stopped. PCM retained; Stop retries saving."
           (guidance 4 true false false)))
    (is (= "STOPPED" (label 0 false false false false)))
    (is (= "REC ENABLED" (label 0 false false false true)))
    (is (= "Listening. Record starts a new take." (guidance 0 true true false)))
    (is (= "Paused. Record starts a new take." (guidance 0 true false true)))
    (is (= "Text only. Select a voiced passage." (guidance 0 false false false)))
    (is (= "Record a new take for this passage." (guidance 0 true false false)))
    (is (= "Recording. Stop to save." (guidance 2 true true false))))
  (let [before (az/value studio/record-track)
        count (az/value scene/passage-entity-count)]
    (try
      (doseq [index [count 4294967295]]
        (az/set-value! studio/record-track index)
        (is (false? (studio/armed-passage?)) "Out-of-range arm IDs are never read"))
      (doseq [index (range count)]
        (az/set-value! studio/record-track index)
        (is (= (not-empty (#'studio/native-string (studio/node-id index)))
               (when (studio/armed-passage?)
                 (#'studio/native-string (studio/node-id index))))))
      (finally
        (az/set-value! studio/record-track before)))))

(az/defstruct GridInputSnapshot {:layout :extern}
  [[focus :bool] [drag :bool] [click :bool]])

(az/defn grid-input-snapshot :- GridInputSnapshot []
  (GridInputSnapshot {:focus studio/name-focus
                     :drag studio/name-drag
                     :click studio/clicked}))

(az/defn restore-grid-input! :- :void [[input GridInputSnapshot]]
  (set! studio/name-focus (az/field input focus))
  (set! studio/name-drag (az/field input drag))
  (set! studio/clicked (az/field input click)))

(deftest take-grid-layout-and-mode-isolation
  (let [fields [studio/workspace-mode studio/workspace-record-prior studio/record-enabled
                studio/window-width studio/window-height studio/editor-top studio/routing-visible
                studio/track-offset studio/track-scroll studio/mouse-x studio/mouse-y
                studio/route-menu studio/trim-drag studio/divider-drag scene/passage-entity-count]
        before (mapv az/value fields)
        input-before (grid-input-snapshot)
        session-fields [studio/selected studio/record-track studio/capture-phase
                        studio/record-mode studio/seek-seconds studio/headphones]
        session-before (mapv az/value session-fields)]
    (try
      (doseq [enabled [0 1]]
        (az/set-value! studio/workspace-mode 0)
        (az/set-value! studio/record-enabled enabled)
        (studio/select-workspace! 2)
        (is (= enabled (az/value studio/record-enabled)))
        (studio/select-workspace! 1)
        (is (= 1 (az/value studio/record-enabled)))
        (studio/select-workspace! 2)
        (is (= enabled (az/value studio/record-enabled)))
        (studio/select-workspace! 0)
        (is (= enabled (az/value studio/record-enabled))))
      (studio/select-workspace! 2)
      (studio/select-workspace! 99)
      (is (= 2 (az/value studio/workspace-mode)))
      (az/set-value! studio/window-width 1100.0)
      (az/set-value! studio/window-height 760.0)
      (az/set-value! studio/editor-top 444.0)
      (az/set-value! studio/routing-visible true)
      (is (= 4 (studio/visible-row-count)))
      (is (= [242.0 456.0 670.0] (mapv studio/take-card-x (range 3))))
      (is (= 206.0 (studio/take-card-width)))
      (az/set-value! scene/passage-entity-count 20)
      (az/set-value! studio/track-offset 0)
      (az/set-value! studio/track-scroll 0.0)
      (az/set-value! studio/mouse-x 500.0)
      (az/set-value! studio/mouse-y 220.0)
      (dotimes [_ 4] (studio/scroll-by! 0.0 -0.3 true true))
      (is (= 1 (az/value studio/track-offset)))
      (studio/scroll-by! 0.0 -1000.0 false false)
      (is (= 16 (az/value studio/track-offset)))
      (studio/scroll-by! 0.0 1000.0 false false)
      (is (= 0 (az/value studio/track-offset)))
      (is (= session-before (mapv az/value session-fields)))
      (finally
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))
        (restore-grid-input! input-before)
        (az/close! input-before)))))

(az/defn paragraph-clipping-contract :- :u32 []
  (let [^{:var [:array 24 mesh/GpuVertex]} scratch ak/undefined
        old-vertices scene/vertices
        old-count scene/vertex-count
        old-width scene/canvas-width
        old-height scene/canvas-height]
    (ak/defer
      (set-studio-state! [scene/vertices old-vertices
                          scene/vertex-count old-count
                          scene/canvas-width old-width
                          scene/canvas-height old-height]))
    (set-studio-state! [scene/vertices (ak/& scratch)
                        scene/vertex-count 0
                        scene/canvas-width 1000.0
                        scene/canvas-height 1000.0])
    (set! _ (studio/paragraph! "A" 20.0 80.0 20.0 1.0 100.0 130.0 0xffffff))
    (when (ak/!= scene/vertex-count 6)
      (ak/return 1))
    (dotimes [i 6]
      (let [vertex (az/index scratch i)
            x (* 500.0 (+ 1.0 (az/field vertex x)))
            y (* 500.0 (+ 1.0 (az/field vertex y)))]
        (when (or (< x 19.99) (> x 40.01) (< y 99.99) (> y 130.01))
          (ak/return 2))))
    (let [first (az/index scratch 0)]
      (when (or (> (ak/abs (- (az/field first wx) 68.0)) 0.01)
                (> (ak/abs (- (az/field first wy) 180.0)) 0.01))
        (ak/return 3)))
    (set! scene/vertex-count 0)
    (set! _ (studio/paragraph! "A" 20.0 80.5 20.0 1.0 100.0 130.0 0xffffff))
    (when (or (ak/!= scene/vertex-count 6)
              (> (ak/abs (- (az/field (az/index scratch 0) wy) 179.5)) 0.01))
      (ak/return 4))
    (set! scene/vertex-count 0)
    (set! _ (studio/paragraph! "A" 20.0 200.0 20.0 1.0 100.0 130.0 0xffffff))
    (when (ak/!= scene/vertex-count 0)
      (ak/return 5))
    (let [width (* 1.5 (az/index scene/glyph-advances 201))]
      (when (<= width 0.0)
        (ak/return 6))
      (let [end (studio/paragraph! "ÉÉÉ" 20.0 0.0 width 1.0 0.0 500.0 0xffffff)]
        (when (or (> (ak/abs (- end 195.0)) 0.01)
                  (ak/!= scene/vertex-count 18))
          (ak/return 7)))))
  0)

(deftest paragraph-clips-glyphs-with-fractional-scroll
  (is (zero? (paragraph-clipping-contract))))

(deftest paragraph-includes-last-line-at-scroll-boundary
  (is (studio/paragraph-line-visible? 204.0 33.8 204.0 550.0))
  (is (studio/paragraph-line-visible? 516.2 33.8 204.0 550.0))
  (is (studio/paragraph-line-visible? 516.2006 33.8 204.0 550.0))
  (is (false? (studio/paragraph-line-visible? 516.3 33.8 204.0 550.0)))
  (is (false? (studio/paragraph-line-visible? 200.0 33.8 204.0 550.0))))

(az/defn grouped-state-order-contract :- :u32 []
  (let [^{:var :u32} counter 0
        ^{:var :u32} observed 0]
    (set-studio-state! [counter (+ counter 1)
                       observed (+ counter 4)
                       counter (+ counter observed)])
    (+ (* counter 10) observed)))

(deftest grouped-state-preserves-native-evaluation-order
  (is (= 65 (grouped-state-order-contract))))

(az/defn focused-record-request-contract :- :u32 []
  (let [old-selected studio/selected
        old-arm studio/record-track
        old-click studio/clicked
        old-text studio/record-request-text
        old-length studio/record-request-length
        old-busy (ak/atomicLoad :u8 (ak/& studio/busy) :.acquire)
        old-pending (ak/atomicLoad :u32 (ak/& studio/pending) :.acquire)
        ^{:var :u32} first-index 4294967295
        ^{:var :u32} second-index 4294967295]
    (ak/defer
      (do
        (set-studio-state! [studio/selected old-selected
                           studio/record-track old-arm
                           studio/clicked old-click
                           studio/record-request-text old-text
                           studio/record-request-length old-length])
        (ak/atomicStore :u8 (ak/& studio/busy) old-busy :.release)
        (ak/atomicStore :u32 (ak/& studio/pending) old-pending :.release)))
    (dotimes [i scene/passage-entity-count]
      (when (> (az/field (studio/node-id (ak/intCast i)) len) 0)
        (if (ak/== first-index 4294967295)
          (set! first-index (ak/intCast i))
          (when (ak/== second-index 4294967295)
            (set! second-index (ak/intCast i))))))
    (when (ak/== second-index 4294967295)
      (ak/return 1))
    (ak/atomicStore :u8 (ak/& studio/busy) 0 :.release)
    (ak/atomicStore :u32 (ak/& studio/pending) 0 :.release)
    (set-studio-state! [studio/record-track first-index
                       studio/selected second-index])
    (studio/request-selected-recording!)
    (when (ak/!= (studio/take-action!) 38)
      (ak/return 2))
    (set! studio/selected first-index)
    (when (ak/! (mem/eql :u8 (studio/requested-recording-id) (studio/node-id second-index)))
      (ak/return 3))
    (when (ak/!= studio/record-track first-index)
      (ak/return 4))
    (ak/atomicStore :u8 (ak/& studio/busy) 0 :.release)
    (set! studio/selected scene/passage-entity-count)
    (studio/request-selected-recording!)
    (when (ak/!= (studio/take-action!) 0)
      (ak/return 5))
    0))

(deftest focused-record-captures-the-selected-id
  ;; Native command construction only, consumed in this same render callback.
  ;; Never opens a microphone or sends the QA request to the real worker.
  (is (= 0 (focused-record-request-contract))))

(defn live-direct-record-countin-qa!
  "Off-render, opt-in native button QA. A 60-second count-in is cancelled before
  microphone capture. Switches between two voiced passages and restores UI state.
  This verifies actual command targeting/cancellation, not recorded PCM or OS input."
  [first-index second-index]
  (let [render #'audition-qa-render!
        fields [studio/selected studio/record-track studio/workspace-mode
                studio/workspace-record-prior studio/record-enabled studio/record-mode
                studio/countdown-seconds studio/track-offset studio/record-scroll
                studio/focus-scroll studio/seek-seconds studio/preview-node studio/preview-paused]
        before (render #(mapv az/value fields))
        project-before @studio/project
        wait-for! (fn [predicate]
                    (let [deadline (+ (System/nanoTime) 5000000000)]
                      (loop []
                        (cond
                          (predicate) true
                          (> (System/nanoTime) deadline)
                          (throw (ex-info "Direct Record QA timed out" {}))
                          :else (do (Thread/sleep 10) (recur))))))
        ids (render #(mapv (fn [index] (#'studio/native-string (studio/node-id index)))
                            [first-index second-index]))]
    (assert (and (nil? @studio/session)
                  (nil? @studio/armed)
                  (render #(and (not (studio/busy?)) (not (az/value studio/voice-ready)))))
            "Direct Record QA requires stopped/unloaded playback and no capture")
    (assert (and (not= first-index second-index) (every? seq ids)))
    (try
      (render #(do
                 (studio/select-workspace! 1)
                 (az/set-value! studio/countdown-seconds 60)
                 (az/set-value! studio/record-enabled 0)
                 (az/set-value! studio/record-track second-index)))
      (mapv
        (fn [index id x y]
          (render #(do
                     (az/set-value! studio/track-offset index)
                     (studio/click-at! 100.0 180.0)))
          (wait-for! #(= index (render (fn [] (az/value studio/selected)))))
          (render #(studio/click-at! x y))
          (wait-for! #(= id (:id @studio/armed)))
          (let [target (render #(hash-map :selected (az/value studio/selected)
                                          :target (az/value studio/record-track)
                                          :phase (studio/capture-phase-value)
                                          :rec-enabled (az/value studio/record-enabled)))]
            (assert (= index (:target target)))
            (assert (= 1 (:phase target)))
            (assert (nil? @studio/session) "Count-in must not open a recording")
            (render #(studio/click-at! 192.0 65.0))
            (wait-for! #(and (nil? @studio/armed)
                             (render (fn [] (not (studio/busy?))))))
            (assert (nil? @studio/session))
            (assert (= project-before @studio/project) "No take or project mutation")
            (assoc target :id id :cancelled true)))
        [first-index second-index first-index]
        [(first ids) (second ids) (first ids)]
        [340.0 350.0 340.0]
        [164.0 65.0 164.0])
      (finally
        (when @studio/armed
          (studio/command! {:op :transport/stop}))
        (when-not @studio/session
          (render #(doseq [[field value] (map vector fields before)]
                     (az/set-value! field value))))))))

(defn- retain-direct-record-qa-takes! [captured before]
  (when (seq captured)
    (let [op (keyword "qa" (str "retain-direct-record-" (java.util.UUID/randomUUID)))
          allowed (set (map :path captured))]
      (studio/register-command! op
        {:description "Label QA recordings and restore previous passage take references"
         :validate empty?
         :handler
         (fn [_]
           (#'studio/change-takes! "QA: retain tests, restore selections"
             (fn [takes]
               (reduce
                 (fn [takes id]
                   (update takes id
                     (fn [entry]
                       (doseq [key [:dry :wet :selected]]
                         (when-not (or (= (get entry key) (get-in before [id key]))
                                        (contains? allowed (get entry key)))
                           (throw (ex-info "User changed a take reference during QA; restore refused"
                                           {:id id :field key}))))
                       (reduce
                         (fn [entry key]
                           (if (contains? (get before id) key)
                             (assoc entry key (get-in before [id key]))
                             (dissoc entry key)))
                         (update entry :history
                           #(mapv (fn [take]
                                    (if (contains? allowed (:path take))
                                      (assoc take :name "QA direct Record - 220/330 Hz test")
                                      take)) %))
                         [:dry :wet :selected]))))
                 takes (distinct (map :id captured)))))
           {:revision (:revision @studio/project)
            :retained (count captured)})})
      (try
        (let [result (studio/command! {:op op :expected-revision (:revision @studio/project)})]
          (when-not (= :done (:status result))
            (throw (ex-info "QA take cleanup did not finish; inspect its request before retrying" result)))
          result)
        (finally (studio/unregister-command! op))))))

(az/defn- publication-game-idle? :- :bool []
  (and (ak/! scene/voice-ready)
       (ak/== scene/voice-node story/no-parent)))

(az/defn- publication-game-mute! :- :bool [[muted :bool]]
  (let [before scene/audio-muted]
    (set! scene/audio-muted muted)
    before))

(defn live-voice-publication-qa!
  "Opt-in publication of an existing QA wet take, followed by guarded restoration.
  Requires idle game voice/Studio transports. Keeps a recovery copy of the original
  published WAV. Tests the real file watcher, without calling update-voice! itself."
  [id path]
  (let [render #'audition-qa-render!
        before (studio/query)
        take-before (get-in before [:takes id])
        entry (some #(when (= path (:path %)) %) (:history take-before))
        destination (io/file "resources/voices" (str id ".wav"))
        digest (fn [file]
                 (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file file)))]
                   (.formatHex (java.util.HexFormat/of)
                     (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))))
        command (fn [request]
                  (let [result (studio/command! request)]
                    (when-not (= :done (:status result))
                      (throw (ex-info "Publication QA command did not complete" result)))
                    result))
        snapshot #(render (fn [] {:revision (az/value scene/voice-revision)
                                  :hash (az/value scene/voice-hash)}))
        await-change (fn [previous]
                       (let [deadline (+ (System/nanoTime) 3000000000)]
                         (loop []
                           (let [current (snapshot)]
                             (cond
                               (and (> (:revision current) (:revision previous))
                                    (not= (:hash current) (:hash previous))) current
                               (> (System/nanoTime) deadline)
                               (throw (ex-info "Game voice watcher did not reload" current))
                               :else (do (Thread/sleep 20) (recur)))))))]
    (assert (re-matches #"voice-[a-zA-Z0-9_-]+" id))
    (assert (= :wet (:kind entry)) "Only an existing processed take may be published")
    (assert (.startsWith (or (:name entry) "") "QA ") "Refuse non-QA takes")
    (assert (.isFile destination) "This test requires an original voice to restore")
    (assert (and (nil? (:recording before))
                 (nil? (:countdown before))
                 (nil? (:input-check before))
                 (not (get-in before [:transport :playing?]))
                 (render publication-game-idle?)))
    (let [original-hash (digest destination)
          candidate-hash (digest path)
          original-time (java.nio.file.Files/getLastModifiedTime (.toPath destination)
                          (make-array java.nio.file.LinkOption 0))
          backup (io/file "build/recording-qa" (str "voice-backup-" (java.util.UUID/randomUUID) ".wav"))
          op (keyword "qa" (str "restore-publication-" (java.util.UUID/randomUUID)))
          original-native (render #(hash-map :hash (az/value scene/voice-hash)
                                             :path (az/value scene/voice-path)
                                             :check-time (az/value scene/voice-check-time)))
          old-mute (atom nil)
          baseline (atom nil)]
      (assert (not= original-hash candidate-hash))
      (io/make-parents backup)
      (java.nio.file.Files/copy (.toPath destination) (.toPath backup)
        (make-array java.nio.file.CopyOption 0))
      (assert (= original-hash (digest backup)))
      (studio/register-command! op
        {:description "Restore the pre-QA selected/published take references"
         :validate empty?
         :handler
         (fn [_]
           (#'studio/change-takes! "QA: restore publication"
             (fn [takes]
               (reduce
                 (fn [takes key]
                   (let [current (get-in takes [id key])]
                     (when-not (or (= current path) (= current (get take-before key)))
                       (throw (ex-info "Take changed during QA; restore refused" {:field key})))
                     (if (contains? take-before key)
                       (assoc-in takes [id key] (get take-before key))
                       (update takes id dissoc key))))
                 takes [:selected :published]))
             true)
           :restored)})
      (try
        (reset! old-mute (render #(publication-game-mute! true)))
        (let [index (render #(#'studio/passage-index id))]
          (assert (render #(scene/load-node-voice! index)))
          (reset! baseline (snapshot)))
        (command {:op :take/select :args {:id id :path path}})
        (command {:op :take/publish})
        (let [published (await-change @baseline)]
          (assert (= candidate-hash (digest destination)))
          (#'studio/atomic-write! destination #(io/copy backup %))
          (let [restored (await-change published)]
            {:before @baseline
             :published published
             :restored restored
             :original-sha256 original-hash
             :candidate-sha256 candidate-hash
             :backup (.getCanonicalPath backup)}))
        (finally
          (try
            (when-not (contains? #{original-hash candidate-hash} (digest destination))
              (throw (ex-info "Published WAV changed outside QA; restore refused"
                              {:backup (.getCanonicalPath backup)})))
            (#'studio/atomic-write! destination #(io/copy backup %))
            (java.nio.file.Files/setLastModifiedTime (.toPath destination) original-time)
            (assert (= original-hash (digest destination)))
            (command {:op op})
            (command {:op :selection/passage :args {:id (:selection before)}})
            (finally
              (render #(do
                         (scene/stop-voice!)
                         (az/set-value! scene/voice-node 4294967295)
                         (az/set-value! scene/voice-hash (:hash original-native))
                         (az/set-value! scene/voice-path (:path original-native))
                         (az/set-value! scene/voice-check-time (:check-time original-native))
                         (when (some? @old-mute)
                           (publication-game-mute! @old-mute))))
              (studio/unregister-command! op))))))))

(defn live-input-check-qa!
  "Opt-in virtual-input preflight. No microphone, take, publication or OS-input claim."
  ([] (live-input-check-qa! false))
  ([fx?]
  (let [render #'audition-qa-render!
        fields [studio/microphone studio/record-mode studio/record-track studio/workspace-mode
                studio/workspace-record-prior studio/record-enabled studio/countdown-seconds]
        before (render #(mapv az/value fields))
        query-before (studio/query)
        source (.indexOf (get-in query-before [:devices :inputs]) "BlackHole 16ch")
        output (.indexOf (get-in query-before [:devices :outputs]) "BlackHole 16ch")
        wait-for! (fn [predicate]
                    (let [deadline (+ (System/nanoTime) 12000000000)]
                      (loop []
                        (cond
                          (predicate) true
                          (> (System/nanoTime) deadline)
                          (throw (ex-info "Input-check QA timed out" {}))
                          :else (do (Thread/sleep 20) (recur))))))]
    (assert (and (>= source 0) (>= output 0)
                  (nil? @studio/session) (nil? @studio/armed) (nil? @studio/input-check)))
    (try
      (render #(do
                 (studio/select-workspace! 1)
                 (az/set-value! studio/microphone source)
                 (az/set-value! studio/record-mode (if fx? 8 1))
                 (assert (start-test-tone! output (if fx? 4 0)))
                 (studio/click-at! 560.0 120.0)))
      (wait-for! #(some? @studio/input-check))
      (wait-for! #(= :signal-present (get-in (studio/query) [:routing-signal :input :state])))
      (let [signal (:routing-signal (studio/query))
            start (System/nanoTime)]
        (wait-for! #(nil? @studio/input-check))
        (assert (not (recorder/input-check-active?)))
        (assert (= (:project query-before) (:project (studio/query))))
        (assert (= (:takes query-before) (:takes (studio/query))))
        ;; A second check hands ownership to Record, without needing another arm.
        (render #(studio/click-at! 560.0 120.0))
        (wait-for! #(some? @studio/input-check))
        (render #(az/set-value! studio/countdown-seconds 3))
        (studio/command! {:op :record/start :args {:id (:selection query-before)}})
        (assert (and (nil? @studio/input-check) (some? @studio/armed)
                      (not (recorder/input-check-active?))))
        (studio/command! {:op :transport/stop})
        {:signal signal
         :auto-stop-wait-seconds (/ (- (System/nanoTime) start) 1e9)
         :record-handoff? true
         :takes-unchanged? (= (:takes query-before) (:takes (studio/query)))})
      (finally
        (studio/command! {:op :transport/stop})
        (render #(do
                   (stop-test-tone!)
                   (doseq [[field value] (map vector fields before)]
                     (az/set-value! field value)))))))))

(defn isolated-interrupted-capture-qa!
  "Run only in a disposable JVM: real BlackHole PCM, native unexpected stop,
  production interrupted-save handler, no system routing or live project edits."
  []
  (assert (nil? @studio/worker) "Never redefine callbacks in a live Studio JVM")
  (assert (recorder/initialize!))
  (let [names (fn [capture? count]
                (mapv #(#'studio/native-string (recorder/device-name capture? %)) (range count)))
        source (.indexOf (names true (az/value recorder/capture-count)) "BlackHole 16ch")
        output (.indexOf (names false (az/value recorder/playback-count)) "BlackHole 16ch")
        path (io/file "build/recording-qa" (str "interrupted-" (java.util.UUID/randomUUID)) "dry.wav")
        takes (atom {})
        session (atom {:id "voice-qa" :path (.getCanonicalPath path) :processed? false})
        warnings (atom [])
        events (atom [])]
    (assert (and (>= source 0) (>= output 0)) "BlackHole must already be installed")
    (io/make-parents path)
    (try
      (assert (start-test-tone! output 0))
      (assert (recorder/start! source output 1 0))
      (let [deadline (+ (System/nanoTime) 5000000000)]
        (loop []
          (when (< (recorder/frames-recorded) 48000)
            (assert (< (System/nanoTime) deadline) "No native capture frames")
            (Thread/sleep 10)
            (recur))))
      (assert (zero? (recorder/stopped-device-mask)))
      (assert (interrupt-recorder-device-qa! false))
      (assert (= 1 (recorder/stopped-device-mask)))
      (with-redefs-fn
        {#'studio/session session
         #'studio/takes takes
         #'studio/armed (atom nil)
         #'studio/checkpoint! (fn [_] nil)
         #'studio/render! (fn [_] nil)
         #'studio/warning! #(swap! warnings conj %)
         #'studio/emit-event! #(swap! events conj %)
         #'studio/remember! (fn [id kind path]
                              (swap! takes update-in [id :history] (fnil conj []) {:path path :kind kind}))
         #'studio/change-takes! (fn [_ change] (swap! takes change))}
        #(#'studio/check-audio-devices!))
      (assert (nil? @session))
      (assert (zero? (recorder/stopped-device-mask)))
      (assert (recorder/validate-take! (.getCanonicalPath path)))
      (assert (true? (get-in @takes ["voice-qa" :history 0 :interrupted?])))
      {:path (.getCanonicalPath path)
       :frames (az/value recorder/measured-frames)
       :peak (az/value recorder/measured-peak)
       :warning (last @warnings)
       :events (mapv :type @events)}
      (finally
        (recorder/stop!)
        (stop-test-tone!)
        (recorder/shutdown!)))))

(defn live-direct-record-capture-qa!
  "Opt-in, off-render native UI + real BlackHole capture/save/audition test.
  Retains labelled QA WAVs/history, restores prior take references and UI/devices.
  No physical microphone, system routing change, game publication or OS-input claim."
  ([indices] (live-direct-record-capture-qa! indices false))
  ([indices fx?] (live-direct-record-capture-qa! indices fx? nil))
  ([indices fx? during-capture!]
  (let [render #'audition-qa-render!
        fields [studio/selected studio/record-track studio/workspace-mode
                studio/workspace-record-prior studio/record-enabled studio/record-mode
                studio/countdown-seconds studio/microphone studio/track-offset
                studio/record-scroll studio/focus-scroll studio/seek-seconds
                studio/preview-node studio/preview-paused studio/compensate
                studio/return-input studio/effects-output studio/tail-seconds studio/monitor-enabled]
        before (render #(mapv az/value fields))
        takes-before @studio/takes
        playback-ready? (render #(az/value studio/playback-ready))
        captured (atom [])
        signal-checks (atom {})
        devices (:devices (studio/query))
        source (.indexOf (:inputs devices) "BlackHole 16ch")
        output (.indexOf (:outputs devices) "BlackHole 16ch")
        wait-for! (fn [label predicate]
                    (let [deadline (+ (System/nanoTime) 8000000000)]
                      (loop []
                        (cond
                          (predicate) true
                          (> (System/nanoTime) deadline)
                          (throw (ex-info (str "Direct capture QA: " label) {}))
                          :else (do (Thread/sleep 10) (recur))))))]
    (assert (and (seq indices) (>= source 0) (>= output 0)))
    (assert (and (nil? @studio/session) (nil? @studio/armed)
                  (render #(and (not (studio/busy?))
                                 (not (az/value studio/voice-ready))
                                 (not (az/value test-tone-running)))))
            "QA requires stopped/unloaded playback and no active capture")
    (try
      (render #(do
                 (studio/select-workspace! 1)
                 (az/set-value! studio/record-enabled 0)
                 (az/set-value! studio/record-mode (if fx? 8 1))
                 (az/set-value! studio/countdown-seconds 0)
                 (az/set-value! studio/microphone source)
                 (when fx?
                   ;; Periodic fixtures cannot establish latency; retain raw pairs.
                   (az/set-value! studio/compensate 0)
                   (az/set-value! studio/return-input source)
                   (az/set-value! studio/effects-output output)
                   (az/set-value! studio/tail-seconds 1)
                   (az/set-value! studio/monitor-enabled 0))
                 (assert (start-test-tone! output (if fx? 4 0)) "Cannot start virtual QA source")))
      (mapv
        (fn [index]
          (let [id (render #(#'studio/native-string (studio/node-id index)))]
            (assert (seq id) "QA target must be a voiced passage")
            (render #(do
                       (az/set-value! studio/track-offset index)
                       (studio/click-at! 100.0 180.0)))
            (wait-for! "passage selection" #(= index (render (fn [] (az/value studio/selected)))))
            (render #(studio/click-at! 340.0 164.0))
            (wait-for! "capture start" #(= id (:id @studio/session)))
            (let [{:keys [path dry-path]} @studio/session]
              (swap! captured conj {:id id :path path})
              (when dry-path
                (swap! captured conj {:id id :path dry-path}))
              (wait-for! "capture one second" #(>= (recorder/frames-recorded) 48000))
              (when during-capture!
                ;; Optional off-render acceptance probe. The same finally block
                ;; retains PCM and restores devices if a probe fails.
                (during-capture! {:id id :index index :path path}))
              (let [signal (:routing-signal (studio/query))]
                (assert (= :signal-present (get-in signal [:input :state])))
                (assert (= (if fx? :signal-present :bypassed) (get-in signal [:return :state])))
                (swap! signal-checks assoc id signal))
              (render #(studio/click-at! 192.0 65.0))
              (wait-for! "save" #(and (nil? @studio/session)
                                       (= path (get-in @studio/takes [id :selected]))
                                       (render (fn [] (not (studio/busy?))))))
              (let [wave (files/waveform path)
                    duration (/ (:frames wave) 48000.0)
                    peak (apply max (:bins wave))]
                (assert (<= 1.0 duration (if during-capture! 30.0 6.0))
                        "Bounded capture, including an optional live-edit probe")
                (assert (> peak (if fx? 0.0001 0.005)) "Saved take must contain the virtual source")
                (assert (recorder/validate-take! path) "Native decoder rejects silent/clipped/invalid PCM")
                ;; Saving precedes the worker's waveform upload. Wait for the
                ;; actual enabled UI, rather than clicking a disabled control.
                (wait-for! "saved-take display"
                           #(and (= [id path] @studio/display-cache)
                                 (render
                                   (fn []
                                     (and (studio/selected-waveform?)
                                          (< (abs (- (studio/selected-take-seconds) duration)) 0.0001))))))
                (render #(studio/click-at! 325.0 (studio/bottom-y 699.0)))
                ;; play-voice-file! resets this counter for each new take. A
                ;; previous longer audition is not a baseline for this one.
                (wait-for! "saved-take audition"
                           #(render (fn [] (and (studio/playing-preview?)
                                                (> (studio/playback-signal-count) 500)))))
                (let [audition (render #(hash-map :cursor (studio/cursor-seconds)
                                                 :signal-frames (studio/playback-signal-count)
                                                 :capture-phase (studio/capture-phase-value)))]
                  (assert (zero? (:capture-phase audition)))
                  (render #(studio/click-at! 192.0 65.0))
                  (wait-for! "audition stop"
                             #(render (fn [] (and (not (az/value studio/voice-ready))
                                                  (not (studio/busy?))))))
                  (let [{:keys [speaker text]} (render #(#'studio/passage-data index))
                        entry (some #(when (= path (:path %)) %) (get-in @studio/takes [id :history]))]
                    {:id id :path path :dry-path dry-path :seconds duration :peak peak :audition audition
                     :dialogue-hash (:dialogue-hash entry)
                     :script-status (studio/recording-freshness (studio/dialogue-fingerprint speaker text) entry)
                     :routing-signal (get @signal-checks id)}))))))
        indices)
      (finally
        (render #(stop-test-tone!))
        (when (or @studio/session @studio/armed)
          (studio/command! {:op :transport/stop})
          (wait-for! "cleanup capture" #(and (nil? @studio/session) (nil? @studio/armed))))
        (retain-direct-record-qa-takes! @captured takes-before)
        (render #(do
                   (studio/stop-voice!)
                   (when-not playback-ready? (studio/close-playback!))
                   (doseq [[field value] (map vector fields before)]
                     (az/set-value! field value))))
        (let [id (render #(#'studio/native-string (studio/selected-id)))
              path (get-in @studio/takes [id :selected])
              expected (if path
                         (files/waveform path)
                         {:frames 0 :bins (vec (repeat 128 0.0))})]
          (wait-for! "restore the selected waveform, not live PCM"
                     #(and (= [id path] @studio/display-cache)
                           (render
                             (fn []
                               (and (< (abs (- (az/value studio/take-seconds)
                                               (/ (:frames expected) 48000.0))) 0.0001)
                                    (every? (fn [[a b]] (< (abs (- a b)) 0.000001))
                                            (map vector (az/value studio/wave) (:bins expected))))))))))))))

(defn live-capture-owner-qa!
  "Real virtual-audio capture with a fault-injected selection change and mode
  switches. Not an OS-input or original-Markdown watcher test. Preserves the
  recording guard; retains labelled QA audio and restores prior take pointers."
  [index other-index]
  (let [render #'audition-qa-render!
        evidence (atom [])
        other (render #(#'studio/native-string (studio/node-id other-index)))
        snapshot (fn []
                   (render #(hash-map :selected (#'studio/native-string (studio/selected-id))
                                      :display-id (#'studio/native-string (studio/waveform-passage-id))
                                      :script (#'studio/native-string (studio/record-script))
                                      :frames (recorder/frames-recorded))))
        captures
        (live-direct-record-capture-qa!
          [index] false
          (fn [{:keys [id]}]
            (let [script (:script @studio/session)]
              (assert (and (seq other) (not= id other)))
              (assert (= id (:id script)))
              ;; Public selection correctly refuses changes during recording.
              ;; Inject the position change that a reordered story can cause.
              (render #(az/set-value! studio/selected other-index))
              (let [state (snapshot)]
                (assert (= other (:selected state)))
                (assert (= id (:display-id state)))
                (assert (= (:text script) (:script state)))
                (swap! evidence conj state))
              (studio/command! {:op :view/mode :args {:mode :edit}})
              (let [state (snapshot)]
                (assert (= other (:display-id state)))
                (swap! evidence conj state))
              (studio/command! {:op :view/mode :args {:mode :record}})
              (let [state (snapshot)]
                (assert (= id (:display-id state)))
                (assert (= (:text script) (:script state)))
                (assert (= script (:script @studio/session)))
                (swap! evidence conj state))
              (render #(az/set-value! studio/selected index)))))]
    (assert (apply <= (map :frames @evidence)) "Capture frames advance across views")
    {:captures captures :selection-stages @evidence}))

(az/defn dense-frame-vertices :- :u32
  [[output [:c-pointer mesh/GpuVertex]]]
  ;; Scratch geometry only: never submit this synthetic layout to Vulkan, write
  ;; a project, or touch an audio device. Caller saves/restores Studio UI fields.
  (let [data (ak/& (az/index scene/stories scene/active-story))
        old-count (az/field data count)
        old-passages scene/passage-entity-count
        old-vertices scene/vertices
        old-vertex-count scene/vertex-count
        old-width scene/canvas-width
        old-height scene/canvas-height
        old-click studio/clicked
        old-press studio/event-pressed
        old-double studio/event-double
        old-test-click studio/test-click
        old-name-focus studio/name-focus
        old-name-drag studio/name-drag
        old-mouse-down studio/mouse-down
        old-double-click studio/double-clicked
        ^{:var [:array 32 story/Node]} nodes (mem/zeroes (az/type [:array 32 story/Node]))
        ^{:var [:array 32 studio/ClipViewportRow]} clips (mem/zeroes (az/type [:array 32 studio/ClipViewportRow]))
        old-grid-offset studio/take-grid-offset
        old-grid-rows studio/take-grid-rows
        ^{:var [:array 48 studio/TakeCard]} cards (mem/zeroes (az/type [:array 48 studio/TakeCard]))]
    (ak/memcpy (ak/& nodes) (az/slice (az/field data nodes) 0 32))
    (ak/memcpy (ak/& clips) (az/slice studio/clip-viewport 0 32))
    (ak/memcpy (ak/& cards) (ak/& studio/take-cards))
    (ak/defer
      (do
        (ak/memcpy (az/slice (az/field data nodes) 0 32) (ak/& nodes))
        (ak/memcpy (az/slice studio/clip-viewport 0 32) (ak/& clips))
        (ak/memcpy (ak/& studio/take-cards) (ak/& cards))
        (set! (az/field data count) old-count)
        (set-studio-state! [studio/take-grid-offset old-grid-offset
                           studio/take-grid-rows old-grid-rows
                           scene/passage-entity-count old-passages
                           scene/vertices old-vertices
                           scene/vertex-count old-vertex-count
                           scene/canvas-width old-width
                           scene/canvas-height old-height
                           studio/clicked old-click
                           studio/event-pressed old-press
                           studio/event-double old-double
                           studio/test-click old-test-click
                           studio/name-focus old-name-focus
                           studio/name-drag old-name-drag
                           studio/mouse-down old-mouse-down
                           studio/double-clicked old-double-click])))
    (dotimes [i 32]
      (set! (az/index (az/field data nodes) i) (az/index nodes 1))
      (set! (az/field (az/index studio/clip-viewport i) seconds) 60.0)
      (set! (az/field (az/index studio/clip-viewport i) start) 0.0)
      (dotimes [bin 128]
        (set! (az/index (az/field (az/index studio/clip-viewport i) wave) bin) 0.5)))
    (set! (az/field data count) 32)
    (dotimes [slot 48]
      (studio/upload-take-card! (ak/intCast slot) true false true 12.0 "A full take card")
      (dotimes [bin 32]
        (studio/upload-take-card-bin! (ak/intCast slot) (ak/intCast bin) 0.5)))
    (set-studio-state! [studio/take-grid-offset 0
                       studio/take-grid-rows 16
                       scene/passage-entity-count 32
                       scene/vertices output
                       scene/vertex-count 0
                       scene/canvas-width studio/window-width
                       scene/canvas-height studio/window-height
                       studio/clicked false
                       studio/event-pressed false
                       studio/event-double false
                       studio/test-click false
                       studio/name-focus false
                       studio/name-drag false
                       studio/mouse-down false
                       studio/double-clicked false])
    (studio/draw!)
    scene/vertex-count))

(defn live-frame-budget-qa!
  "Run on core/on-render!: stress both workspaces without changing real takes."
  []
  (assert (not (studio/busy?)))
  (let [fields [studio/window-width studio/window-height studio/editor-top
                studio/workspace-mode studio/page studio/selected studio/record-track
                studio/timeline-start studio/timeline-seconds studio/track-offset
                studio/track-snapshot studio/track-snapshot-count studio/route-menu
                studio/route-click studio/routing-visible studio/mix-mode
                studio/trim-drag studio/divider-drag
                studio/focus-scroll studio/focus-height studio/record-scroll studio/record-text-height
                studio/scroll studio/content-height studio/follow-playhead]
        before (mapv az/value fields)]
    (try
      (with-open [arena (java.lang.foreign.Arena/ofConfined)]
        (let [buffer (.allocate arena (* 64 (az/value gpu/frame-capacity)) 16)]
          (mapv
            (fn [[width height mode page menu]]
              (doseq [[field value]
                      [[studio/window-width width] [studio/window-height height]
                       [studio/editor-top (- height 300.0)] [studio/workspace-mode mode]
                       [studio/page page] [studio/selected 0] [studio/record-track 0]
                       [studio/timeline-start 0.0] [studio/timeline-seconds 60.0]
                       [studio/track-offset 0] [studio/track-snapshot 0]
                       [studio/track-snapshot-count 32] [studio/route-menu menu]
                       [studio/routing-visible true] [studio/mix-mode false]
                       [studio/trim-drag 0] [studio/divider-drag false]
                       [studio/focus-scroll 0.0] [studio/record-scroll 0.0]
                       [studio/scroll 0.0] [studio/follow-playhead false]]]
                (az/set-value! field value))
              {:size [width height] :mode mode :page page :menu menu
               :vertices (dense-frame-vertices buffer)})
            [[1100.0 760.0 0 0 0]
             [1427.0 881.0 0 0 0]
             [2560.0 2160.0 0 0 1]
             [2560.0 2160.0 0 1 1]
             [1100.0 760.0 1 0 0]
             [2560.0 2160.0 1 0 1]
             [1100.0 760.0 2 0 0]
             [2560.0 2160.0 2 0 1]])))
      (finally
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))))))

(deftest dense-studio-layout-fits-frame-budget
  (when (az/value studio/attached)
    (doseq [{:keys [vertices] :as result} (live-frame-budget-qa!)]
      (is (pos? vertices) (pr-str result))
      (is (< vertices (az/value gpu/frame-capacity)) (pr-str result)))))

(az/defn selected-cursor-contract :- :u32 []
  (let [ready studio/voice-ready mix studio/mix-mode node studio/preview-node
        selected studio/selected seek studio/seek-seconds]
    (ak/defer (do (set! studio/voice-ready ready) (set! studio/mix-mode mix)
      (set! studio/preview-node node) (set! studio/selected selected)
      (set! studio/seek-seconds seek)))
    (set! studio/mix-mode false) (set! studio/voice-ready false)
    (set! studio/selected 5) (set! studio/preview-node 4294967295)
    (set! studio/seek-seconds 3.0)
    (when (ak/!= (studio/cursor-seconds) 3.0) (ak/return 1))
    (when (ak/!= (studio/selected-take-cursor 0.0) 3.0) (ak/return 2))
    ;; Only the pure selection helper runs with simulated ready state: no fake
    ;; sound is ever passed to miniaudio. Real paused playback is live QA below.
    (set! studio/voice-ready true) (set! studio/preview-node 5)
    (when (ak/!= (studio/selected-take-cursor 4.0) 4.0) (ak/return 3))
    (set! studio/selected 6)
    (when (ak/!= (studio/selected-take-cursor 4.0) 3.0) (ak/return 4))
    (set! studio/selected 5) (set! studio/mix-mode true)
    (when (ak/!= (studio/selected-take-cursor 4.0) 3.0) (ak/return 5)))
  0)

(deftest selected-waveform-uses-its-own-position
  (is (= 0 (selected-cursor-contract))
      "Unloaded seek, current preview, other passage and mix have explicit cursor ownership"))

(az/defn reset-timing-storage-for-layout! {:attrs #{:export}} :- :void
  [[old-address :usize] [new-address :usize]]
  ;; Explicit opt-in migration for disposable profiling history only. Never
  ;; use this for takes, devices, audio buffers or project state.
  (set! _ old-address)
  (let [^{:zig/type [:* [:array 240 studio/FrameTiming]]} destination
        (ak/ptrFromInt new-address)]
    (set! (az/deref destination) (mem/zeroes (az/type [:array 240 studio/FrameTiming])))))

(az/defn waveform-ownership-contract :- :u32 []
  (let [owner studio/waveform-owner
        owner-length studio/waveform-owner-length
        uploaded studio/waveform-uploaded
        selected studio/selected
        seconds studio/take-seconds
        busy studio/busy
        preview studio/preview-node
        sample (az/index studio/wave 0)]
    (ak/defer
      (set-studio-state! [studio/waveform-owner owner
                         studio/waveform-owner-length owner-length
                         studio/waveform-uploaded uploaded
                         studio/selected selected
                         studio/busy busy
                         studio/take-seconds seconds]))
    (set-studio-state! [studio/selected 1 studio/take-seconds 12.0 studio/busy 0])
    (studio/waveform-owner! (studio/node-id 1))
    (when (ak/!= (studio/selected-take-seconds) 12.0) (ak/return 1))
    (when (ak/! (studio/take-editable?)) (ak/return 5))
    (set! studio/take-seconds 0.0)
    (when (studio/take-editable?) (ak/return 6))
    (set-studio-state! [studio/take-seconds 12.0 studio/busy 1])
    (when (studio/take-editable?) (ak/return 7))
    (set! studio/busy 0)
    (set! studio/selected 3)
    (when (or (studio/selected-waveform?)
              (studio/take-editable?)
              (ak/!= (studio/selected-take-seconds) 0.0))
      (ak/return 2))
    (when (or (ak/!= studio/preview-node preview)
              (ak/!= (az/index studio/wave 0) sample))
      (ak/return 3))
    (set! studio/selected 1)
    (when (ak/!= (studio/selected-take-seconds) 12.0) (ak/return 4)))
  0)

(deftest selected-waveform-belongs-to-its-passage
  (is (= 0 (waveform-ownership-contract))
      "Only the loaded, nonempty, idle selected take enables editing"))

(az/defn comparison-readiness-contract :- :u32 []
  (let [owner studio/comparison-owner
        owner-length studio/comparison-owner-length
        code studio/comparison-code
        wave-owner studio/waveform-owner
        wave-owner-length studio/waveform-owner-length
        uploaded studio/waveform-uploaded
        selected studio/selected
        seconds studio/take-seconds
        busy studio/busy]
    (ak/defer
      (set-studio-state! [studio/comparison-owner owner
                         studio/comparison-owner-length owner-length
                         studio/comparison-code code
                         studio/waveform-owner wave-owner
                         studio/waveform-owner-length wave-owner-length
                         studio/waveform-uploaded uploaded
                         studio/selected selected
                         studio/take-seconds seconds
                         studio/busy busy]))
    (set-studio-state! [studio/selected 1
                       studio/take-seconds 12.0
                       studio/busy 0])
    (studio/waveform-owner! (studio/node-id 1))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-unmarked)
    (when (studio/comparison-enabled?) (ak/return 1))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-select-b)
    (when (studio/comparison-enabled?) (ak/return 2))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-listen-a)
    (when (ak/! (studio/comparison-enabled?)) (ak/return 3))
    (when (ak/! (mem/eql (az/type :u8) (studio/comparison-label) "A/B: listen A"))
      (ak/return 4))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-listen-b)
    (when (ak/! (studio/comparison-enabled?)) (ak/return 5))
    (set! studio/busy 1)
    (when (studio/comparison-enabled?) (ak/return 6))
    (set-studio-state! [studio/busy 0 studio/take-seconds 0.0])
    (when (studio/comparison-enabled?) (ak/return 7))
    (set! studio/take-seconds 12.0)
    (studio/comparison-view! (studio/node-id 3) studio/comparison-listen-a)
    (when (studio/comparison-enabled?) (ak/return 8))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-missing-a)
    (when (studio/comparison-enabled?) (ak/return 9))
    (studio/comparison-view! (studio/node-id 1) studio/comparison-missing-b)
    (when (studio/comparison-enabled?) (ak/return 10)))
  0)

(deftest native-comparison-controls-reflect-reference-readiness
  (is (= 0 (comparison-readiness-contract))
      "Only a ready pair belonging to the loaded, idle selected passage enables comparison"))

(az/defn capture-script-ownership-contract :- :u32 []
  (let [id studio/capture-id-text
        id-length studio/capture-id-length
        text studio/capture-script-text
        text-length studio/capture-script-length
        selected studio/selected
        phase studio/capture-phase
        mode studio/workspace-mode
        count scene/passage-entity-count
        data (ak/& (az/index scene/stories scene/active-story))
        first-node (az/index (az/field data nodes) 1)
        second-node (az/index (az/field data nodes) 3)]
    (ak/defer
      (do
        (set-studio-state! [studio/capture-id-text id
                           studio/capture-id-length id-length
                           studio/capture-script-text text
                           studio/capture-script-length text-length
                           studio/selected selected
                           studio/capture-phase phase
                           studio/workspace-mode mode
                           scene/passage-entity-count count])
        (set! (az/index (az/field data nodes) 1) first-node)
        (set! (az/index (az/field data nodes) 3) second-node)))
    (when (ak/! (studio/capture-script! (studio/node-id 1) "Captured words"))
      (ak/return 1))
    (set-studio-state! [studio/workspace-mode 1
                       studio/capture-phase 2
                       studio/selected 3])
    (when (or (ak/! (studio/capture-passage? 1))
              (studio/capture-passage? 3)
              (ak/! (mem/eql (az/type :u8) (studio/record-script) "Captured words"))
              (ak/! (mem/eql (az/type :u8) (studio/waveform-passage-id) (studio/node-id 1))))
      (ak/return 2))
    ;; Simulate a Markdown reorder atomically, before any frame can observe it.
    (set! (az/index (az/field data nodes) 1) second-node)
    (set! (az/index (az/field data nodes) 3) first-node)
    (when (or (studio/capture-passage? 1) (ak/! (studio/capture-passage? 3)))
      (ak/return 3))
    ;; Even removal cannot erase the script being spoken or redirect the PCM.
    (set! scene/passage-entity-count 0)
    (when (or (studio/capture-passage? 3)
              (ak/! (mem/eql (az/type :u8) (studio/record-script) "Captured words")))
      (ak/return 4))
    (set! scene/passage-entity-count count)
    (set! studio/workspace-mode 0)
    (when (ak/! (mem/eql (az/type :u8) (studio/waveform-passage-id) (studio/node-id studio/selected)))
      (ak/return 5))
    (set! studio/capture-phase 0)
    (when (ak/! (mem/eql (az/type :u8) (studio/record-script) (scene/story-text studio/selected)))
      (ak/return 6)))
  0)

(deftest recording-script-and-waveform-survive-selection-and-reordering
  (is (= 0 (capture-script-ownership-contract))))

(deftest passage-status-badges-are-readable-and-bounded
  (doseq [[status label] [[0 "Checking"] [1 "No take"] [2 "Review"]
                          [3 "Recorded"] [4 "Unverified"] [5 "Interrupted"] [6 "Text only"]]]
    (let [text (studio/passage-status-badge status)]
      (is (<= (studio/ui-text-width text 0.19) 64.0)
          "The badge text fits its fixed-width track header without overlapping")
      ;; native-string consumes/closes the returned slice after copying it.
      (is (= label (#'studio/native-string text))))))

(az/defn passage-freshness-contract :- :u32 []
  (let [index (ak/as :u32 1)
        saved (az/index studio/passage-freshness index)
        id (studio/node-id index)
        revision (studio/node-revision index)]
    (ak/defer (set! (az/index studio/passage-freshness index) saved))
    (studio/set-passage-freshness! index id revision 2)
    (when (ak/! (mem/eql (az/type :u8) (studio/passage-freshness-label index) "Changed - review take"))
      (ak/return 1))
    (studio/set-passage-freshness! index "different-id" revision 3)
    (studio/set-passage-freshness! index id (+ revision 1) 3)
    (when (ak/! (mem/eql (az/type :u8) (studio/passage-freshness-label index) "Changed - review take"))
      (ak/return 2))
    (studio/set-passage-freshness! index id revision 3)
    (when (ak/! (mem/eql (az/type :u8) (studio/passage-freshness-label index) "Recorded"))
      (ak/return 3))
    (studio/set-passage-freshness! index id revision 1)
    (when (ak/! (mem/eql (az/type :u8) (studio/passage-freshness-label index) "Needs recording"))
      (ak/return 4)))
  0)

(deftest passage-freshness-rejects-stale-row-uploads
  (is (= 0 (passage-freshness-contract))))

(az/defn frame-timing-ring-contract :- :u32 []
  (let [saved studio/frame-timings
        count studio/frame-timing-count
        index studio/frame-timing-index
        previous studio/frame-previous-start
        build studio/frame-build-ms]
    (ak/defer
      (set-studio-state! [studio/frame-timings saved
                         studio/frame-timing-count count
                         studio/frame-timing-index index
                         studio/frame-previous-start previous
                         studio/frame-build-ms build]))
    (studio/reset-frame-timings!)
    (set! studio/frame-build-ms 2.0)
    (dotimes [i 301]
      (let [started (+ 1.0 (* 0.01 (ak/as :f64 (ak/floatFromInt i))))]
        (studio/record-frame-timing! started (+ started 0.003))))
    (when (or (ak/!= studio/frame-timing-count 240)
              (ak/!= studio/frame-timing-index 60))
      (ak/return 1))
    (let [sample (az/index studio/frame-timings 59)]
      (when (or (> (ak/abs (- (az/field sample interval-ms) 10.0)) 0.001)
                (> (ak/abs (- (az/field sample build-ms) 2.0)) 0.001)
                (> (ak/abs (- (az/field sample render-ms) 3.0)) 0.001))
        (ak/return 2)))
    (studio/reset-frame-timings!)
    (studio/record-frame-timing! 100.0 100.003)
    (when (ak/!= studio/frame-timing-count 0) (ak/return 3)))
  0)

(deftest frame-timing-history-is-bounded-and-resets-cadence
  (is (= 0 (frame-timing-ring-contract))))

(az/defn routing-tools-presentation-contract :- :u32 []
  (let [tools studio/routing-tools-visible
        clicked studio/clicked
        busy studio/busy
        checking recorder/input-check-running
        route studio/route-menu
        pending studio/pending]
    (ak/defer
      (set-studio-state! [studio/routing-tools-visible tools
                         studio/clicked clicked
                         studio/busy busy
                         recorder/input-check-running checking]))
    (set-studio-state! [studio/busy 0
                       recorder/input-check-running 0
                       studio/clicked true])
    (studio/show-routing-tools! true)
    (when (or (ak/! studio/routing-tools-visible) studio/clicked
              (ak/! (studio/routing-controls-available?)))
      (ak/return 1))
    (set! studio/busy 1)
    (when (studio/routing-controls-available?) (ak/return 2))
    (studio/show-routing-tools! false)
    (when studio/routing-tools-visible (ak/return 3))
    (set-studio-state! [studio/busy 0
                       recorder/input-check-running 1])
    (when (studio/routing-controls-available?) (ak/return 4))
    (when (or (ak/!= pending studio/pending) (ak/!= route studio/route-menu))
      (ak/return 5)))
  0)

(deftest routing-tools-disclosure-does-not-change-audio
  (is (= 0 (routing-tools-presentation-contract))
      "Disclosure only changes presentation; device controls distinguish idle/capture/check"))

(az/defn monitor-level-control-contract :- :u32 []
  (let [gain (studio/monitor-level)
        x studio/mouse-x
        y studio/mouse-y
        clicked studio/clicked
        down studio/mouse-down
        dragging studio/monitor-level-drag
        visible studio/routing-visible
        menu studio/route-menu
        enabled studio/monitor-enabled
        audition studio/audition-gain]
    (ak/defer
      (do
        (studio/set-monitor-level! gain)
        (set-studio-state! [studio/mouse-x x
                           studio/mouse-y y
                           studio/clicked clicked
                           studio/mouse-down down
                           studio/monitor-level-drag dragging
                           studio/routing-visible visible
                           studio/route-menu menu])))
    (when (or (ak/!= (studio/monitor-level-at (studio/right-x 800.0)) 0)
              (ak/!= (studio/monitor-level-at (studio/right-x 992.0)) 25)
              (ak/!= (studio/monitor-level-at (studio/right-x 1200.0)) 50))
      (ak/return 1))
    (set-studio-state! [studio/routing-visible true
                       studio/route-menu 0
                       studio/mouse-x (studio/right-x 945.2)
                       studio/mouse-y 508.0
                       studio/clicked true
                       studio/mouse-down true])
    (studio/update-monitor-level!)
    (when (or (ak/!= (studio/monitor-level) 10) studio/clicked
              (ak/! studio/monitor-level-drag))
      (ak/return 2))
    (set-studio-state! [studio/mouse-x (studio/right-x 800.0)
                       studio/mouse-y 550.0])
    (studio/update-monitor-level!)
    (when (ak/!= (studio/monitor-level) 0) (ak/return 3))
    (set! studio/mouse-down false)
    (studio/update-monitor-level!)
    (when studio/monitor-level-drag (ak/return 4))
    (set-studio-state! [studio/monitor-level-drag true
                       studio/route-menu 4
                       studio/mouse-x (studio/right-x 1200.0)])
    (studio/update-monitor-level!)
    (when (or studio/monitor-level-drag (ak/!= (studio/monitor-level) 0))
      (ak/return 5))
    (set-studio-state! [studio/route-menu 0
                       studio/routing-visible false
                       studio/monitor-level-drag true])
    (studio/update-monitor-level!)
    (when (or studio/monitor-level-drag (ak/!= (studio/monitor-level) 0)
              (ak/!= enabled studio/monitor-enabled)
              (ak/!= audition studio/audition-gain))
      (ak/return 6)))
  0)

(deftest monitor-level-fader-is-bounded-and-independent
  (is (= 0 (monitor-level-control-contract))
      "Click/drag/release, off-track clamp, hidden/menu cancellation; no enable or take gain change"))

(az/defn monitor-meter-contract :- :u32 []
  (when (or recorder/running recorder/source-running recorder/monitoring) (ak/return 1))
  (let [peak recorder/return-peak-ppm held recorder/return-held-ppm gain recorder/monitor-gain
        ^{:var [:array 32 :f32]} input (mem/zeroes (az/type [:array 32 :f32]))
        ^{:var [:array 4 :f32]} output (mem/zeroes (az/type [:array 4 :f32]))]
    (ak/defer (do (set! recorder/return-peak-ppm peak) (set! recorder/return-held-ppm held)
                 (set! recorder/monitor-gain gain)))
    (set! recorder/monitor-gain 15)
    (set! (az/index input 0) 0.9) ; Send channels must never feed the return meter.
    (set! (az/index input 2) 0.25) (set! (az/index input 19) -0.5)
    (recorder/process-monitor! (ak/& output) (ak/& input) 2)
    (when (> (ak/abs (- (recorder/signal-peak false false) 0.5)) 0.00001) (ak/return 2))
    (when (or (> (ak/abs (- (az/index output 0) 0.0375)) 0.00001)
              (> (ak/abs (+ (az/index output 3) 0.075)) 0.00001)) (ak/return 3))
    (studio/set-monitor-level! 0)
    (recorder/process-monitor! (ak/& output) (ak/& input) 2)
    (when (or (ak/!= (az/index output 0) 0.0)
              (ak/!= (az/index output 3) 0.0))
      (ak/return 5))
    (studio/set-monitor-level! 100)
    (recorder/process-monitor! (ak/& output) (ak/& input) 2)
    (when (or (ak/!= (studio/monitor-level) 50)
              (> (ak/abs (- (az/index output 0) 0.125)) 0.00001)
              (> (ak/abs (+ (az/index output 3) 0.25)) 0.00001)
              (> (ak/abs (- (recorder/signal-peak false false) 0.5)) 0.00001))
      (ak/return 6))
    (recorder/process-monitor! (ak/& output) ak/null 2)
    (when (ak/!= (recorder/signal-peak false false) 0.0) (ak/return 4)))
  0)

(deftest monitor-updates-return-meter-without-recording
  (is (= 0 (monitor-meter-contract)) "Return 3/4, pre-monitor gain peak and silence reset"))

(deftest live-meter-display-not-held-history
  (is (= 0.0 (studio/meter-fraction false 0.8)))
  (is (= 0.0 (studio/meter-fraction true -1.0)))
  (is (= 1.0 (studio/meter-fraction true 2.0)))
  (is (= 0.5 (studio/meter-fraction true 0.25)))
  (doseq [input? [true false]]
    (when-not (studio/meter-active? input?)
      (is (zero? (studio/live-meter-fraction input?))))))

(deftest daw-pointer-navigation
  (let [fields [studio/window-width studio/window-height studio/routing-visible studio/editor-top
                studio/timeline-start studio/timeline-seconds studio/follow-playhead
                studio/mouse-x studio/mouse-y studio/focus-scroll studio/focus-height
                studio/page studio/track-scroll studio/track-offset studio/route-menu studio/workspace-mode]
        before (mapv az/value fields)]
    (try
      (az/set-value! studio/workspace-mode 0)
      (az/set-value! studio/window-width 1100.0) (az/set-value! studio/window-height 760.0)
      (az/set-value! studio/routing-visible true)
      (az/set-value! studio/editor-top 444.0)
      (az/set-value! studio/route-menu 0)
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
      (az/set-value! studio/timeline-start 10.0) (az/set-value! studio/timeline-seconds 30.0)
      (az/set-value! studio/mouse-x 563.0)
      (let [anchor (studio/time-at 563.0) offset (az/value studio/track-offset)]
        (studio/scroll-by! 0.0 1.0 true false)
        (is (< (az/value studio/timeline-seconds) 30.0))
        (is (< (abs (- anchor (studio/time-at 563.0))) 0.00001))
        (is (= offset (az/value studio/track-offset)))
        (studio/scroll-by! 0.0 -1.0 true false)
        (is (< (abs (- 30.0 (az/value studio/timeline-seconds))) 0.00001))
        (is (< (abs (- anchor (studio/time-at 563.0))) 0.00001))
        (let [start (az/value studio/timeline-start)]
          (studio/scroll-by! 0.0 -1.0 false true)
          (is (> (az/value studio/timeline-start) start))
          (is (= offset (az/value studio/track-offset)))))
      (let [view (mapv az/value [studio/timeline-start studio/timeline-seconds studio/track-offset])]
        (studio/scrolled! nil 1.0 1.0)
        (is (= view (mapv az/value [studio/timeline-start studio/timeline-seconds studio/track-offset]))
            "A missing callback window must not enter GLFW")
        (az/set-value! studio/route-menu 1)
        (studio/scroll-by! -10.0 -10.0 false false)
        (studio/scroll-by! 0.0 10.0 true false)
        (is (= view (mapv az/value [studio/timeline-start studio/timeline-seconds studio/track-offset]))
            "A modal device menu blocks track scrolling, panning and zooming underneath it"))
      (finally (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

(az/defn keyboard-contract! :- :bool []
  (let [focus studio/name-focus length studio/name-length old-busy studio/busy old-pending studio/pending
        visible studio/attached first-byte (az/index studio/edit-name 0)
        caret studio/name-caret anchor studio/name-anchor view studio/name-view]
    (ak/defer (do (set! studio/name-focus focus) (set! studio/name-length length)
                  (set! studio/name-caret caret) (set! studio/name-anchor anchor) (set! studio/name-view view)
                  (set! studio/busy old-busy) (set! studio/pending old-pending)
                  (set! studio/attached visible) (set! (az/index studio/edit-name 0) first-byte)))
    (set! studio/attached true) (set! studio/name-focus false)
    (set! studio/busy 0) (set! studio/pending 0)
    (studio/key-event! ak/null 32 0 1 0)
    (when (ak/!= (studio/take-action!) 6) (ak/return false))
    (set! studio/busy 0) (set! studio/name-focus true) (studio/name! "")
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

(az/defn name-editing-contract! :- :bool []
  (let [draft (studio/name-draft)
        history studio/name-history
        position studio/name-history-position
        end studio/name-history-end
        batch studio/name-batch
        recorded studio/name-batch-recorded
        focus studio/name-focus
        visible studio/attached
        old-busy studio/busy]
    (ak/defer
      (do
        (studio/name-restore! draft)
        (set-studio-state! [studio/name-history history
                            studio/name-history-position position
                            studio/name-history-end end
                            studio/name-batch batch
                            studio/name-batch-recorded recorded
                            studio/name-focus focus
                            studio/attached visible
                            studio/busy old-busy])))
    (set! studio/attached true) (set! studio/name-focus true) (set! studio/busy 0)
    (studio/name! "café")
    (studio/key-event! ak/null 263 0 1 0)
    (when (ak/!= studio/name-caret 3) (ak/return false))
    (studio/typed! ak/null 88)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "cafXé")) (ak/return false))
    (studio/key-event! ak/null 261 0 1 0)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "cafX")) (ak/return false))
    (studio/key-event! ak/null 263 0 1 1)
    (studio/typed! ak/null 232)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "cafè")) (ak/return false))
    (studio/key-event! ak/null 65 0 1 8)
    (studio/typed! ak/null 128663)
    (when (or (ak/!= studio/name-length 4) (ak/!= studio/name-caret 4)) (ak/return false))
    (studio/key-event! ak/null 259 0 1 0)
    (when (ak/!= studio/name-length 0) (ak/return false))
    (dotimes [_ 119] (studio/typed! ak/null 97))
    (studio/typed! ak/null 233)
    (when (ak/!= studio/name-length 119) (ak/return false))
    (studio/typed! ak/null 98)
    (when (ak/!= studio/name-length 120) (ak/return false))
    (studio/key-event! ak/null 65 0 1 2)
    (studio/typed! ak/null 233)
    (when (or (ak/!= studio/name-length 2) (ak/! (mem/eql :u8 (studio/entered-name) "é"))) (ak/return false))
    (studio/name! "")
    (studio/name-paste! "Prise\tété\nvoix")
    (when (ak/! (mem/eql :u8 (studio/entered-name) "Prise été voix")) (ak/return false))
    (studio/name! "a") (set! studio/name-anchor 0)
    (studio/name-paste! "abc")
    (mem/eql :u8 (studio/entered-name) "abc")))

(az/defn name-edge-scroll-contract :- :u32 []
  (let [draft (studio/name-draft)
        history studio/name-history
        position studio/name-history-position
        end studio/name-history-end
        elapsed studio/name-drag-elapsed]
    (ak/defer
      (do
        (studio/name-restore! draft)
        (set-studio-state! [studio/name-history history
                            studio/name-history-position position
                            studio/name-history-end end
                            studio/name-drag-elapsed elapsed])))
    (studio/name! "Été dialogue — prise longue pour vérifier le défilement horizontal et sa sélection complète")
    (set-studio-state! [studio/name-view 0
                        studio/name-caret 0
                        studio/name-anchor 0
                        studio/name-drag-elapsed 0.0])
    (dotimes [_ 30]
      (studio/name-drag-to! 232.0 (/ 1.0 30.0)))
    (let [view-at-30 studio/name-view
          caret-at-30 studio/name-caret]
      (when (or (ak/== view-at-30 0)
                (ak/!= studio/name-anchor 0)
                (ak/== (& (az/index studio/edit-name view-at-30) 192) 128))
        (ak/return 1))
      (set-studio-state! [studio/name-view 0
                          studio/name-caret 0
                          studio/name-drag-elapsed 0.0])
      (dotimes [_ 120]
        (studio/name-drag-to! 232.0 (/ 1.0 120.0)))
      (when (or (ak/!= studio/name-view view-at-30)
                (ak/!= studio/name-caret caret-at-30))
        (ak/return 2)))
    (dotimes [_ 400]
      (studio/name-drag-to! 260.0 (/ 1.0 120.0)))
    (when (or (ak/!= studio/name-caret studio/name-length)
              (>= studio/name-view studio/name-length))
      (ak/return 3))
    (set! studio/name-anchor studio/name-length)
    (dotimes [_ 400]
      (studio/name-drag-to! 0.0 (/ 1.0 120.0)))
    (when (or (ak/!= studio/name-view 0)
              (ak/!= studio/name-caret 0)
              (ak/!= studio/name-anchor studio/name-length))
      (ak/return 4))
    (set! studio/name-drag-elapsed 0.03)
    (studio/name-drag-to! 100.0 0.01)
    (when (ak/!= studio/name-drag-elapsed 0.0)
      (ak/return 5)))
  0)

(deftest name-edge-scroll-is-time-based
  (is (zero? (name-edge-scroll-contract))
      "30/120 FPS agree; both edges reach UTF-8 boundaries without blank overscroll"))

(deftest unicode-name-editing
  (is (name-editing-contract!)))

(az/defn name-undo-contract! :- :u32 []
  (let [draft (studio/name-draft) history studio/name-history
        position studio/name-history-position end studio/name-history-end
        batch studio/name-batch recorded studio/name-batch-recorded
        focus studio/name-focus attached studio/attached busy studio/busy pending studio/pending]
    (ak/defer (do (studio/name-restore! draft) (set! studio/name-history history)
                  (set! studio/name-history-position position) (set! studio/name-history-end end)
                  (set! studio/name-batch batch) (set! studio/name-batch-recorded recorded)
                  (set! studio/name-focus focus) (set! studio/attached attached)
                  (set! studio/busy busy) (set! studio/pending pending)))
    (set! studio/name-focus true) (set! studio/attached true) (set! studio/busy 0)
    (set! studio/name-batch false) (set! studio/pending 0)
    (studio/name! "café") (studio/typed! ak/null 33)
    (studio/key-event! ak/null 90 0 1 8)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "café")) (ak/return 1))
    (studio/key-event! ak/null 90 0 1 9)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "café!")) (ak/return 2))
    (studio/key-event! ak/null 65 0 1 8)
    (studio/name-paste! "Une\tvoix 🚗")
    (when (ak/! (mem/eql :u8 (studio/entered-name) "Une voix 🚗")) (ak/return 3))
    (studio/name-undo! false)
    (when (or (ak/! (mem/eql :u8 (studio/entered-name) "café!"))
              (ak/!= studio/name-anchor 0) (ak/!= studio/name-caret 6)) (ak/return 4))
    (studio/name-undo! true)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "Une voix 🚗")) (ak/return 5))
    (studio/key-event! ak/null 259 0 1 0)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "Une voix ")) (ak/return 6))
    (studio/name-undo! false)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "Une voix 🚗")) (ak/return 7))
    (studio/name-undo! false) (studio/typed! ak/null 88) (studio/name-undo! true)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "X")) (ak/return 8))
    (when (ak/!= studio/pending 0) (ak/return 9))
    (studio/name! "a")
    (dotimes [_ 40] (studio/typed! ak/null 98))
    (dotimes [_ 40] (studio/name-undo! false))
    (when (ak/!= studio/name-length 9) (ak/return 10))
    (dotimes [_ 40] (studio/name-undo! true))
    (when (ak/!= studio/name-length 41) (ak/return 11))
    (studio/name! "fresh") (studio/name-undo! false)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "fresh")) (ak/return 12))
    (studio/typed! ak/null 33) (set! studio/busy 1) (studio/name-undo! false)
    (when (ak/! (mem/eql :u8 (studio/entered-name) "fresh!")) (ak/return 13))
    0))

(deftest name-draft-undo-redo
  (is (zero? (name-undo-contract!))
      "Draft undo/redo preserves UTF-8 and selection, batches paste, bounds history and leaves project commands alone"))

(deftest unicode-name-load-boundary
  (let [draft (studio/name-draft)
        fields [studio/name-history
                studio/name-history-position
                studio/name-history-end]
        before (mapv az/value fields)]
    (try
      (studio/name! (str (apply str (repeat 119 "a")) "é"))
      (is (= 119 (az/value studio/name-length)))
      (is (= (apply str (repeat 119 "a")) ((var studio/native-string) (studio/entered-name))))
      (studio/name! (str (apply str (repeat 118 "a")) "é"))
      (is (= 120 (az/value studio/name-length)))
      (is (.endsWith ((var studio/native-string) (studio/entered-name)) "é"))
      (finally
        (studio/name-restore! draft)
        (az/close! draft)
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))))))

(az/defn route-key! :- :void [[key :u32] [action :u32]]
  (studio/key-event! ak/null (ak/intCast key) 0 (ak/intCast action) 0))
(az/defn route-test-flags :- :u32 []
  (+ (ak/as :u32 (if studio/name-focus 1 0)) (ak/as :u32 (if studio/name-drag 2 0))
     (ak/as :u32 (if studio/clicked 4 0)) (ak/as :u32 (if studio/route-click 8 0))))
(az/defn restore-route-test-flags! :- :void [[flags :u32]]
  (set! studio/name-focus (ak/!= (& flags 1) 0))
  (set! studio/name-drag (ak/!= (& flags 2) 0))
  (set! studio/clicked (ak/!= (& flags 4) 0))
  (set! studio/route-click (ak/!= (& flags 8) 0)))

(deftest routing-menu-navigation
  ;; Run on the render thread while capture is idle; no hardware is opened.
  (let [fields [studio/route-menu studio/route-focus studio/route-offset studio/microphone
                studio/busy studio/trim-drag recorder/capture-count studio/pending]
        before (mapv az/value fields) flags (route-test-flags)]
    (try
      (az/set-value! studio/busy 0) (az/set-value! studio/pending 0)
      (az/set-value! recorder/capture-count 19) (az/set-value! studio/microphone 10)
      (studio/route-open! 1)
      (is (= [1 10 8] (mapv az/value [studio/route-menu studio/route-focus studio/route-offset])))
      (route-key! 264 1)
      (is (= 11 (az/value studio/route-focus)))
      (is (= 10 (az/value studio/microphone)) "Arrow navigation is not a routing change")
      (route-key! 269 1)
      (is (= [18 16] (mapv az/value [studio/route-focus studio/route-offset])))
      (route-key! 264 2)
      (is (= 18 (az/value studio/route-focus)))
      (route-key! 268 1)
      (route-key! 257 1)
      (is (= [0 0] (mapv az/value [studio/route-menu studio/microphone])))
      (studio/route-open! 1) (route-key! 32 1)
      (is (= 0 (az/value studio/pending)) "Space must not start transport behind a modal menu")
      (route-key! 256 1)
      (is (= 0 (az/value studio/route-menu)))
      (az/set-value! recorder/capture-count 0)
      (studio/route-open! 1) (studio/route-move! true) (studio/route-select! 0)
      (is (= [1 0 0] (mapv az/value [studio/route-menu studio/route-focus studio/microphone])))
      (az/set-value! studio/busy 1) (studio/route-open! 4)
      (is (= 1 (az/value studio/route-menu)) "Busy capture cannot open a different selector")
      (finally
        (doseq [[field value] (map vector fields before)] (az/set-value! field value))
        (restore-route-test-flags! flags)))))

(deftest scrollbar-grab-preserves-position
  (let [before (az/value studio/bar-grab)
        editor-top (az/value studio/editor-top)]
    (try
      (az/set-value! studio/editor-top 444.0)
      (az/set-value! studio/bar-grab 23.0)
      (is (= 4 (studio/visible-row-count)))
      (is (= 8 (studio/track-offset-at (+ 169.0 23.0 (* 0.5 (- 262.0 (* 262.0 (/ 4.0 20.0))))) 20)))
      (is (= 0 (studio/track-offset-at -500.0 20)))
      (is (= 16 (studio/track-offset-at 1000.0 20)))
      (is (= 0 (studio/track-offset-at 200.0 0)))
      (is (= 0 (studio/track-offset-at 200.0 4)))
      (finally
        (az/set-value! studio/bar-grab before)
        (az/set-value! studio/editor-top editor-top)))))

(defn- load-test-assets! []
  ;; Headless tests do not call scene/initialize!, which normally loads these.
  ;; Use the real prepared metrics and dialogue without opening a window or mic.
  (require-isolated-recorder!)
  (doseq [[field path] [[scene/glyph-advances "resources/demo/glyph-advances.bin"]
                        [scene/ui-glyph-advances "resources/demo/ui-glyph-advances.bin"]]]
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
          buffer (doto (java.nio.ByteBuffer/wrap bytes)
                   (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
      (when-not (= 1024 (alength bytes))
        (throw (ex-info "Invalid test font metrics" {:path path})))
      (az/set-value! field (vec (repeatedly 256 #(.getFloat buffer))))))
  (when-not (scene/reload-story!)
    (throw (ex-info "Prepared dialogue could not load for native tests" {}))))

(defn -main [& _]
  (load-test-assets!)
  (let [result (run-tests 'la-professeure.studio-test 'la-professeure.takes-test 'la-professeure.studio-api-test 'la-professeure.mixer-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
