(ns la-professeure.studio-test
  (:require [aguafria.std]
            [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak] [aguafria.std.mem :as mem]
            [la-professeure.scene :as scene]
            [la-professeure.gpu :as gpu]
            [la-professeure.core :as core]
            [la-professeure.miniaudio :as audio]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.mesh :as mesh]
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
(az/defn resize-window-qa! :- :void [[studio? :bool] [width :u32] [height :u32]]
  (glfw/glfwSetWindowSize (if studio? studio/studio-window scene/window)
                         (ak/intCast width) (ak/intCast height)))

(az/defn studio-renderer-snapshot :- gpu/RendererSnapshot []
  (gpu/swap-context! (ak/& studio/renderer))
  (ak/defer (gpu/swap-context! (ak/& studio/renderer)))
  (gpu/renderer-snapshot))

(az/defn studio-resize-count :- :u64 [] (az/field studio/renderer resize-count))

(az/defn maximize-studio-qa! :- :void [[maximize? :bool]]
  (if maximize?
    (glfw/glfwMaximizeWindow studio/studio-window)
    (glfw/glfwRestoreWindow studio/studio-window)))

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
    (when (or (ak/!= (studio/visible-row-count) 6) (ak/!= (studio/track-height) 262.0)
              (ak/!= (studio/editor-text-height) 96.0)) (ak/return 1))
    (studio/set-editor-top! -1000.0)
    (when (or (ak/!= studio/editor-top 316.0) (ak/!= (studio/visible-row-count) 3)) (ak/return 2))
    (studio/set-editor-top! 10000.0)
    (when (or (ak/!= studio/editor-top 460.0) (< (studio/editor-text-height) 80.0)) (ak/return 3))
    (set! studio/window-height 900.0) (studio/set-editor-top! 444.0)
    (set! studio/route-menu 0) (set! studio/double-clicked false) (set! studio/mouse-down true)
    (set! studio/clicked true) (set! studio/mouse-x 300.0) (set! studio/mouse-y 445.0)
    (studio/divider-input!)
    (when (or (ak/! studio/divider-drag) studio/clicked (ak/!= studio/editor-top 444.0)) (ak/return 4))
    (set! studio/mouse-y 601.0) (studio/divider-input!)
    (when (or (ak/!= studio/editor-top 600.0) (ak/!= (studio/visible-row-count) 9)
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
                            (render #(resize-window-qa! true width height))
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
      (is (= "Count-in running. Stop to cancel." (text 1 enabled? true)))
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
  (let [original studio/edit-name length studio/name-length caret studio/name-caret
        anchor studio/name-anchor view studio/name-view focus studio/name-focus
        visible studio/attached old-busy studio/busy]
    (ak/defer (do (set! studio/edit-name original) (set! studio/name-length length)
                  (set! studio/name-caret caret) (set! studio/name-anchor anchor) (set! studio/name-view view)
                  (set! studio/name-focus focus) (set! studio/attached visible) (set! studio/busy old-busy)))
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
  (let [original ((var studio/native-string) (studio/entered-name))
        fields [studio/name-caret studio/name-anchor studio/name-view]
        before (mapv az/value fields)]
    (try
      (studio/name! (str (apply str (repeat 119 "a")) "é"))
      (is (= 119 (az/value studio/name-length)))
      (is (= (apply str (repeat 119 "a")) ((var studio/native-string) (studio/entered-name))))
      (studio/name! (str (apply str (repeat 118 "a")) "é"))
      (is (= 120 (az/value studio/name-length)))
      (is (.endsWith ((var studio/native-string) (studio/entered-name)) "é"))
      (finally
        (studio/name! original)
        (doseq [[field value] (map vector fields before)] (az/set-value! field value))))))

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
      (is (= 7 (studio/track-offset-at (+ 169.0 23.0 (* 0.5 (- 262.0 (* 262.0 (/ 6.0 20.0))))) 20)))
      (is (= 0 (studio/track-offset-at -500.0 20)))
      (is (= 14 (studio/track-offset-at 1000.0 20)))
      (is (= 0 (studio/track-offset-at 200.0 0)))
      (is (= 0 (studio/track-offset-at 200.0 6)))
      (finally
        (az/set-value! studio/bar-grab before)
        (az/set-value! studio/editor-top editor-top)))))

(defn -main [& _]
  (let [result (run-tests 'la-professeure.studio-test 'la-professeure.takes-test 'la-professeure.studio-api-test 'la-professeure.mixer-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
