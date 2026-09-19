(ns field-lab.mesh-cache-test
  (:require [pitoco.geometry :as geometry]
            [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria-examples-native.mesh :as gpu]
            [aguafria-examples-native.renderer :as renderer]
            [aguafria-examples-native.bindings.glfw :as vk]
            [aguafria-examples-native.readback :as readback]
            [field-lab.fem :as fem]
            [field-lab.physics :as p]
            [field-lab.mesh-cache :as cache]
            [field-lab.nonlinear-job :as job]
            [field-lab.fem-job :as linear]
            [field-lab.scene :as scene]
            [field-lab.surface :as surface]))

(az/defstruct Emission {:layout :extern}
  [[:vertices :u32] [:minimum-normal-length :f64] [:maximum-normal-length :f64]])

(az/defn measure-emission! Emission
  []
  (let [storage (fem/allocate gpu/GpuVertex 32768)]
    (defer ((az/field heap/page_allocator free) storage))
    (let [written (surface/emit-bounded! (az/field storage ptr) 32768 0.55 0.3 9.0)
          ^{:var :f64} minimum 1.0e30
          ^{:var :f64} maximum 0.0]
      (dotimes [index written]
        (let [vertex (az/index storage index)
              length (p/length (p/v (ak/floatCast (az/field vertex nx))
                                     (ak/floatCast (az/field vertex ny))
                                     (ak/floatCast (az/field vertex nz))))]
          (az/set-many!
            minimum (ak/min minimum length)
            maximum (ak/max maximum length))))
      (Emission {:vertices written :minimum-normal-length minimum :maximum-normal-length maximum}))))

(deftest oriented-refined-boundary
  (doseq [level [0 1 2]]
    (let [{:keys [points] :as mesh} (nth (iterate job/refine (job/sphere-mesh 0.45 [0.0 0.0 0.0])) level)
          faces (geometry/boundary-faces mesh)
          signs (map (fn [[a b c]]
                       (linear/dot (points a)
                                   (linear/cross (linear/subtract (points b) (points a))
                                                 (linear/subtract (points c) (points a))))) faces)]
      (is (= (* 80 (long (Math/pow 4 level))) (count faces)))
      (is (every? pos? signs)))))

(deftest owned-cache-replay-and-rendered-mesh
  (scene/initialize!)
  (try
    (let [result (job/bake-cache! {:refinement 1 :seconds 0.05})
          owned (:cache result)
          initial (az/value (cache/position owned 0 12))
          final (az/value (cache/position owned 12 12))
          observation (:observation (az/value (cache/frame-info owned 12)))]
      (scene/adopt-cache! owned)
      (is (= 205 (:nodes result)))
      (is (= 13 (az/value scene/count)))
      ;; An undeformed body must not acquire cancellation-scale material energy
      ;; while passing through the compiled assembly and cache writer.
      (is (< (abs (get-in (az/value (cache/frame-info owned 0))
                         [:observation :elastic-energy]))
             1.0e-20))
      (is (< (abs (- (- (:y initial) (:y final)) (* 0.5 9.81 0.05 0.05))) 1.0e-10))
      (scene/seek! 12)
      (is (= (:center observation) (:position (az/value (scene/state)))))
      (scene/seek! 0)
      (is (= initial (az/value (cache/position owned 0 12))))
      (scene/seek! 12)
      (is (= final (az/value (cache/position owned 12 12))))
      (is (false? (scene/step!)))
      (let [emission (az/value (measure-emission!))]
        (is (= (* 6 (:faces result)) (:vertices emission)))
        (is (< 0.999999 (:minimum-normal-length emission)))
        (is (> 1.000001 (:maximum-normal-length emission))))
      (scene/set-solver! 0 10000.0)
      (is (nil? (az/value (scene/mesh-cache))))
      (is (= 1 (az/value scene/count))))
    (finally (scene/shutdown!))))

(az/defn rejects-short-stream! :bool
  []
  (let [^:var sentinel (gpu/GpuVertex {:x 123.0 :y 0.0 :z 0.0 :r 0.0 :g 0.0 :b 0.0
                                      :nx 0.0 :ny 0.0 :nz 0.0 :wx 0.0 :wy 0.0 :wz 0.0
                                      :roughness 0.0 :vx 0.0 :vy 0.0 :vz 0.0})
        written (surface/emit-bounded! (ak/& sentinel) 1 0.55 0.3 9.0)]
    (and (ak/== written 0) surface/stream-overflow (ak/== (az/field sentinel x) 123.0))))

(az/defn exercise-visibility-publication! :u32
  []
  (let [storage (fem/allocate :u32 90000)
        output (az/field storage ptr)
        ^{:var :u32} passed 0]
    (defer ((az/field heap/page_allocator free) storage))
    (defer (surface/set-visibility-target! null 0))
    (surface/set-visibility-target! output 90000)
    (set! _ (measure-emission!))
    (when (and surface/visibility-ready (ak/== (az/index output 0) 0x5049544f)
               (ak/== (az/index output 1) 1) (ak/== (az/index output 3) 0))
      (ak/+= passed 1))
    ;; Same pointer with a smaller capacity must invalidate the cached packet.
    (surface/set-visibility-target! output 16)
    (set! _ (measure-emission!))
    (when (and (ak/! surface/visibility-ready) (ak/== (az/index output 0) 0))
      (ak/+= passed 1))
    (surface/set-visibility-target! output 90000)
    (set! _ (measure-emission!))
    (when surface/visibility-ready (ak/+= passed 1))
    (scene/seek! 1)
    (surface/set-visibility-target! output 90000)
    (set! _ (measure-emission!))
    (when (and surface/visibility-ready (ak/== (az/index output 3) 1)) (ak/+= passed 1))
    ;; Switching to measured geometry at the same tick must replace topology.
    (surface/request-preview! false)
    (surface/set-visibility-target! output 90000)
    (set! _ (measure-emission!))
    (let [packet (az/index output 4)]
      (when (and surface/visibility-ready (ak/== (az/index output (+ packet 1)) 205)
                 (ak/== (az/index output (+ packet 2)) 320))
        (ak/+= passed 1)))
    (scene/seek! 0)
    (surface/request-preview! true)
    passed))

(deftest embedded-preview-preserves-cache-and-retires-on-replacement
  (scene/initialize!)
  (try
    (let [{:keys [cache]} (job/bake-cache! {:refinement 1 :seconds (/ 1.0 240.0)
                                          :config {:gravity 0.0}})]
      (scene/adopt-cache! cache)
      (let [revision (az/value scene/revision)
            measured (az/value (cache/position cache 0 12))]
        (surface/request-preview! true)
        (is (rejects-short-stream!))
        (let [emission (az/value (measure-emission!))]
          (is (= (* 6 2208) (:vertices emission)))
          (is (< 0.999999 (:minimum-normal-length emission)))
          (is (> 1.000001 (:maximum-normal-length emission))))
        (is (= 1 (az/value surface/embedded-count)))
        (is (= 5 (exercise-visibility-publication!)))
        (is (= revision (az/value scene/revision)))
        (is (= measured (az/value (cache/position cache 0 12))))
        (is (= 0 (az/value scene/cursor)))
        (surface/request-preview! false)
        (is (= (* 6 320) (:vertices (az/value (measure-emission!)))))
        (scene/set-solver! 0 10000.0)
        ;; A following frame retires the sidecar without dereferencing its old,
        ;; already freed source cache.
        (surface/request-preview! true)
        (measure-emission!)
        (is (= 0 (az/value surface/embedded-count)))))
    (finally
      (surface/request-preview! false)
      (surface/clear-embeddings!)
      (scene/shutdown!))))


(az/defn tile-coordinates [:array 4 :u32]
  [[width :u32] [height :u32] [edge :u32] [index :u32]]
  (let [rectangle (renderer/render-tile-rectangle width height edge index)
        offset (az/field rectangle offset)
        extent (az/field rectangle extent)]
    (az/array-init [:array 4 :u32]
                   [(ak/intCast (az/field offset x)) (ak/intCast (az/field offset y))
                    (az/field extent width) (az/field extent height)])))

(deftest tiled-rendering-covers-frame-without-overlap
  (doseq [[width height edge] [[1 1 64] [63 65 64] [257 259 256] [301 137 64]
                              [2560 1640 256] [16384 16383 512] [257 259 0]]]
    (let [size (renderer/render-tile-count width height edge)
          rectangles (mapv (fn [index]
                             (let [[x y w h] (az/value (tile-coordinates width height edge index))]
                               {:offset {:x x :y y} :extent {:width w :height h}}))
                           (range size))
          y-boundaries (sort (set (mapcat (fn [{:keys [offset extent]}]
                                          [(:y offset) (+ (:y offset) (:height extent))]) rectangles)))]
      (is (= (* width height) (reduce + (map #(* (get-in % [:extent :width])
                                                  (get-in % [:extent :height])) rectangles))))
      (is (= [0 height] [(first y-boundaries) (last y-boundaries)]))
      (is (every? (fn [{:keys [offset extent]}]
                    (and (<= 0 (:x offset)) (<= 0 (:y offset))
                         (pos? (:width extent)) (pos? (:height extent))
                         (<= (+ (:x offset) (:width extent)) width)
                         (<= (+ (:y offset) (:height extent)) height)
                         (or (zero? edge) (and (<= (:width extent) edge) (<= (:height extent) edge)))))
                  rectangles))
      ;; An independent horizontal sweep checks every y slab, including cropped
      ;; edge tiles. Each slab must consist of adjacent intervals from 0 to width.
      (doseq [[lower upper] (partition 2 1 y-boundaries)]
        (let [y (/ (+ lower upper) 2)
              intervals (sort (for [{:keys [offset extent]} rectangles
                                   :when (< (:y offset) y (+ (:y offset) (:height extent)))]
                               [(:x offset) (+ (:x offset) (:width extent))]))]
          (is (= 0 (ffirst intervals)))
          (is (= width (second (last intervals))))
          (is (every? (fn [[a b]] (= (second a) (first b))) (partition 2 1 intervals))))))))

(az/defvar error-probe-continued :bool false)

(az/defn device-loss-probe! :bool
  []
  (renderer/frame-check (ak/as vk/VkResult vk/VK_ERROR_DEVICE_LOST))
  (set! error-probe-continued true)
  true)

(az/defn stopped-frame-builder :u32
  {:attrs #{:export}}
  [[output [:c-pointer gpu/GpuVertex]] [width :i32] [height :i32]]
  (set! _ output)
  (set! _ width)
  (set! _ height)
  (set! error-probe-continued true)
  0)

(az/defn stopped-render-probe! :bool
  []
  (renderer/render! (ak/& stopped-frame-builder)))

(deftest device-loss-stops-frames-without-aborting
  ;; Isolated headless process only: never inject a device error into the live JVM.
  (let [previous-loss (az/value renderer/device-lost-state)
        previous-readback (az/value readback/state)]
    (try
      (az/set-value! renderer/device-lost-state 0)
      (az/set-value! error-probe-continued false)
      (az/set-value! readback/state 2)
      (is (false? (device-loss-probe!)))
      (is (renderer/device-lost?))
      (is (false? (az/value error-probe-continued)))
      (is (= 5 (readback/status)))
      ;; An uninitialized headless renderer must return before any Vulkan call
      ;; or frame callback. New readbacks must fail promptly while stopped.
      (az/set-value! readback/state 2)
      (is (false? (stopped-render-probe!)))
      (is (= 5 (readback/status)))
      (is (false? (az/value error-probe-continued)))
      (finally
        (az/set-value! renderer/device-lost-state previous-loss)
        (az/set-value! readback/state previous-readback)))))


(deftest playback-keeps-wall-time-after-slow-frames
  (doseq [[cursor count elapsed looping expected ended]
          [[0 1000 1.0 false 240 false]
           [10 145 1.0 true 105 false]
           [10 145 2.0 true 55 false]
           [10 145 2.0 false 144 true]
           [0 145 0.0 true 0 false]
           [0 0 1.0 true 0 true]
           [0 1 1.0 true 0 false]]]
    (let [result (az/value (cache/playback-step cursor count elapsed (/ 1.0 240) looping))]
      (is (= expected (:cursor result)))
      (is (= ended (:ended result)))
      (is (<= 0.0 (:remainder result) (/ 1.0 240)))))
  (let [result (az/value (cache/playback-step 10 145 1e12 (/ 1.0 240) true))]
    (is (<= 0 (:cursor result) 144))))
