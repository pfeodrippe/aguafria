(ns racing-game.render3d-test
  (:require [aguafria.zig :as az]
            [aguafria-examples-native.mesh :as mesh]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [racing-game.geometry :as geometry]
            [racing-game.render3d :as render3d]
            [racing-game.render :as render]
            [racing-game.physics :as physics]
            [racing-game.simulation :as simulation]))

(deftest gpu-instance-layout-test
  ;; Check the real native bytes against Vulkan binding offsets, not just the
  ;; number of Clojure fields. This test does not modify the running race.
  (doseq [[constructor fields byte-count]
          [[mesh/GpuInstance
            [:qx :qy :qz :qw :x :y :z :scale
             :origin_x :origin_y :origin_z :shadow_z :r :g :b :mode] 64]
           [mesh/InstanceCamera
            [:x :y :z :zoom :cos_yaw :sin_yaw :cos_pitch :sin_pitch
             :fit_x :fit_y :reserved0 :reserved1] 48]]]
    (let [values (mapv float (range 1 (inc (count fields))))
          instance (constructor (zipmap fields values))
          segment (az/native-segment instance)]
      (is (= byte-count (.byteSize segment)))
      (is (= values (vec (.toArray segment java.lang.foreign.ValueLayout/JAVA_FLOAT)))))))

(deftest gpu-mesh-sharing-test
  (let [parts (edn/read-string (slurp (io/resource "geometry/racing.edn")))
        cars (dissoc parts :garage)
        vertices (reduce + (map count (vals cars)))
        instance-count (* 20 2 (count cars))]
    (is (= 6 (count cars)) "Body, driver and four independently posed wheels")
    (is (= 240 instance-count) "Twenty instances per part, plus their shadows")
    (is (<= instance-count (az/value mesh/instance-capacity)))
    (is (< (* instance-count 64) (/ (* vertices 20 64) 100))
        "Per-frame instance traffic is under 1% of expanded car vertices")
    (doseq [[_ mesh] cars]
      (is (= (geometry/mesh-revision mesh) (geometry/mesh-revision (vec mesh))))
      (is (not= (geometry/mesh-revision mesh)
                (geometry/mesh-revision (update-in mesh [0 0] + 0.001)))
          "An edited Blender vertex must invalidate the immutable upload"))))

(deftest presentation-pose-interpolation-test
  (let [base (merge (zipmap [:x :y :z :qx :qy :qz :vx :vy :vz :wx :wy :wz] (repeat 0.0))
                    {:qw 1.0})
        a (physics/BodyState base)
        b (physics/BodyState (assoc base :x 10.0 :y 4.0 :z 2.0 :qw -1.0 :vx 30.0))
        midpoint (az/value (render3d/interpolate-pose a b 0.5))]
    (is (= [5.0 2.0 1.0] (mapv midpoint [:x :y :z])))
    (is (= [0.0 0.0 0.0 1.0] (mapv midpoint [:qx :qy :qz :qw]))
        "q and -q represent the same rotation; never interpolate through zero")
    (is (= 30.0 (:vx midpoint)) "Physical velocity is metadata, not overwritten by smoothing")
    (is (= 0.0 (:x (az/value a))) "Input native snapshots remain unmodified")
    (is (= 0.0 (:x (az/value (render3d/interpolate-pose a b -1.0)))))
    (is (= 10.0 (:x (az/value (render3d/interpolate-pose a b 2.0)))))
    (let [turned (physics/BodyState (assoc base :qz 1.0 :qw 0.0))
          half (az/value (render3d/interpolate-pose a turned 0.5))]
      (is (< (Math/abs (- (:qz half) (Math/sqrt 0.5))) 1.0e-6))
      (is (< (Math/abs (- (:qw half) (Math/sqrt 0.5))) 1.0e-6)))))

(deftest fixed-step-presentation-cadence-test
  ;; Reproduce the actual 120Hz physics / ~34Hz rendering mismatch, without
  ;; involving a live world. Constant physical velocity must display linearly.
  (let [base (merge (zipmap [:x :y :z :qx :qy :qz :vx :vy :vz :wx :wy :wz] (repeat 0.0))
                    {:qw 1.0})
        errors
        (for [frame (range 1 121)
              :let [time (/ frame 34.0)
                    ticks (* time 120.0)
                    current (long (Math/floor ticks))
                    phase (- ticks current)
                    a (physics/BodyState (assoc base :x (/ (dec current) 4.0)))
                    b (physics/BodyState (assoc base :x (/ current 4.0)))
                    actual (:x (az/value (render3d/interpolate-pose a b phase)))
                    expected (* 30.0 (- time (/ 1.0 120.0)))]]
          (Math/abs (- actual expected)))]
    (is (< (apply max errors) 0.00002)
        "30m/s displayed at 34FPS has no alternating 3/4-tick staircase")))

(deftest conservative-triangle-rejection-test
  (let [point (fn [[x y z]] (render3d/Vec3 {:x x :y y :z z}))
        visible? (fn [points]
                   (apply render3d/ndc-triangle-visible? (map point points)))]
    (is (visible? [[0 0 0.5] [0.5 0 0.5] [0 0.5 0.5]]))
    ;; A triangle can cover the viewport with no vertex inside it.
    (is (visible? [[-2 -2 0.5] [2 -2 0.5] [0 2 0.5]]))
    (is (visible? [[-1 -1 0] [1 -1 1] [0 1 0.5]]))
    (doseq [[axis outside] [[0 -2] [0 2] [1 -2] [1 2] [2 -1] [2 2]]]
      (is (false? (visible? (mapv #(assoc % axis outside)
                                  [[0 0 0.5] [0.5 0 0.5] [0 0.5 0.5]])))))))

(deftest blender-triangles-test
  (doseq [[kind vertices] (edn/read-string (slurp (io/resource "geometry/racing.edn")))]
    (is (#{:body :driver :wheel-front-left :wheel-front-right
           :wheel-rear-left :wheel-rear-right :garage} kind))
    (is (zero? (mod (count vertices) 3)))
    (is (every? #(= 10 (count %)) vertices))
    (is (every? #(Double/isFinite (double %)) (mapcat identity vertices)))
    (is (> (apply max (map #(nth % 2) vertices)) 0.1))))

(deftest blender-physical-proportions-test
  (let [parts (edn/read-string (slurp (io/resource "geometry/racing.edn")))
        bounds (fn [vertices axis]
                 [(apply min (map #(nth % axis) vertices))
                  (apply max (map #(nth % axis) vertices))])
        [bottom top] (bounds (:driver parts) 2)]
    ;; A hidden Blender collection once yielded an identity matrix_world and
    ;; turned the helmet and gloves into 2.1m spheres beneath the floor.
    (is (> bottom 0.1))
    (is (< 0.8 top 1.7))
    (doseq [wheel [:wheel-front-left :wheel-front-right
                  :wheel-rear-left :wheel-rear-right]]
      (let [[low high] (bounds (wheel parts) 2)]
        (is (<= -0.1 low 0.15))
        (is (< 0.8 high 1.1))))))

(deftest blender-wheel-axle-test
  (let [meshes (edn/read-string (slurp (io/resource "geometry/racing.edn")))
        axles (edn/read-string (slurp (io/resource "geometry/wheel-axes.edn")))]
    (is (= #{:wheel-front-left :wheel-front-right :wheel-rear-left :wheel-rear-right}
           (set (keys axles))))
    (doseq [[part {:keys [center radius width]}] axles]
      (is (< 0.3 radius 0.6))
      (is (< 0.3 width 0.8))
      (doseq [axis [0 2]]
        (let [coordinates (map #(nth % axis) (meshes part))
              lo (apply min coordinates) hi (apply max coordinates)]
          (is (< (Math/abs (- (- hi lo) (* 2 radius))) 1.0e-6))
          (is (< (Math/abs (- (* 0.5 (+ lo hi)) (nth center axis))) 1.0e-6)))))))

(deftest native-camera-and-frame-test
  (simulation/reset!)
  ;; Overview is the worst case for the shared buffer: the complete circuit,
  ;; Blender containment and garages must fit. Cars/shadows now use a separate
  ;; immutable mesh + bounded instance stream, validated by the instance tests.
  (render3d/camera-preset! 4)
  (render3d/update-camera!)
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [capacity mesh/frame-capacity
          ;; A guard after the vertex stream catches writes past its ABI bound.
          buffer (.allocate arena (+ (* capacity 64) 8) 8)]
      (.set buffer java.lang.foreign.ValueLayout/JAVA_LONG (* capacity 64) 424242)
      (doseq [[width height] [[1600 900] [900 1600] [900 900]]]
        (let [count (render3d/build-world-geometry! buffer width height)
              origin (az/value (render3d/project 0.0 0.0 0.0))
              raised (az/value (render3d/project 0.0 0.0 0.1))
              ;; Bulk-read once: reflective MemorySegment.getAtIndex in a
              ;; million-element loop spends minutes resolving overloads.
              ^floats stream (.toArray (.asSlice ^java.lang.foreign.MemorySegment buffer
                                                 0 (long (* count 64)))
                                      java.lang.foreign.ValueLayout/JAVA_FLOAT)]
          (is (< 3000 count capacity) (str "Non-instanced overview vertex count: " count))
          (is (zero? (mod count 3)))
          (is (< (:y raised) (:y origin)))
          (is (< (:z raised) (:z origin)))
          (is (= 424242 (.get buffer java.lang.foreign.ValueLayout/JAVA_LONG
                              (* capacity 64))))
          (is (every? #(Float/isFinite (aget stream %)) (range (* count 16))))
          (is (every? #(<= 0.0 (aget stream (+ (* % 16) 2)) 1.0) (range count)))
          (is (every? (fn [i]
                        (let [offset (* i 16)
                              roughness (aget stream (+ offset 12))]
                          (or (< roughness 0.0)
                              (and (<= 0.0 roughness 1.0)
                                   (< 0.98 (+ (Math/pow (aget stream (+ offset 6)) 2)
                                              (Math/pow (aget stream (+ offset 7)) 2)
                                              (Math/pow (aget stream (+ offset 8)) 2))
                                      1.02)))))
                      (range count))))))))

(deftest spectator-camera-and-minimap-test
  (simulation/reset!)
  (render3d/follow-leaders!)
  (render3d/zoom-by! 0.0001)
  (is (= 1.0 (:zoom (az/value (render3d/camera-snapshot)))))
  (render3d/zoom-by! 3.0)
  (render3d/update-camera!)
  (let [camera (az/value (render3d/camera-snapshot))]
    (is (:following camera))
    (is (:front_pack camera))
    (is (= 3.0 (:zoom camera))))
  (render3d/zoom-by! 1000.0)
  (is (= 80.0 (:zoom (az/value (render3d/camera-snapshot)))))
  (render3d/select-camera! 5 true)
  (render3d/update-camera!)
  (let [camera (az/value (render3d/camera-snapshot))
        racer (az/value (simulation/racer-view 5))]
    (is (not (:front_pack camera)))
    (is (= 5 (:racer camera)))
    (is (= (:x racer) (:x camera)))
    (is (= (:y racer) (:y camera))))
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [buffer (.allocate arena (* mesh/frame-capacity 64) 8)]
      (doseq [[w h] [[1600 900] [900 1600] [900 900]]]
        (render/configure-world-scale! w h)
        (let [n (render/append-minimap! buffer 0 w h)
              coords (for [i (range n)]
                       [(.getAtIndex buffer java.lang.foreign.ValueLayout/JAVA_FLOAT (* i 16))
                        (.getAtIndex buffer java.lang.foreign.ValueLayout/JAVA_FLOAT (inc (* i 16)))])]
          (is (< 1000 n 4000))
          (is (every? (fn [[x y]] (and (< 0.0 x 1.0) (< -1.0 y 0.0))) coords))))))
  (render3d/zoom-by! 0.0001)
  (render3d/zoom-by! 3.0)
  (render3d/follow-leaders!))

(deftest wheel-distance-and-camera-presets-test
  (let [radius (float 0.44)
        turn-progress (float (/ (* 2.0 Math/PI radius) 4309.0))
        angle (render3d/wheel-roll turn-progress 0 radius)]
    (is (< (min (Math/abs angle) (Math/abs (- angle (* 2 Math/PI)))) 1.0e-4))
    (let [a (render3d/wheel-roll 0.999999 0 radius)
          b (render3d/wheel-roll 0.0 1 radius)
          delta (- b a)]
      (is (< (Math/abs (Math/atan2 (Math/sin delta) (Math/cos delta))) 0.02)))
    (is (> (render3d/wheel-roll (float (/ radius 4309.0)) 0 radius) 0.99)))
  (doseq [mode (range 5)]
    (render3d/camera-preset! mode)
    (render3d/update-camera!)
    (let [camera (az/value (render3d/camera-snapshot))]
      (is (every? #(Double/isFinite (double %))
                  (map camera [:x :y :z :zoom])))
      (is (<= 1.0 (:zoom camera) 80.0))))
  (render3d/camera-preset! 0))

(deftest frame-rate-independent-camera-damping-test
  (let [sample (fn [hz]
                 (reduce (fn [position _]
                           (render3d/damp position 10.0 8.0 (float (/ 1.0 hz))))
                         0.0 (range hz)))
        values (mapv sample [30 60 120 240])]
    (is (< (- (apply max values) (apply min values)) 0.0001))
    (is (every? #(< 9.99 % 10.0) values))
    (is (= 3.0 (render3d/damp 3.0 10.0 8.0 0.0)))
    (is (= 3.0 (render3d/damp 3.0 10.0 8.0 -1.0))))
  (let [current (float (Math/toRadians 179.0))
        target (float (Math/toRadians -179.0))
        result (render3d/damp-angle current target 8.0 (float (/ 1.0 60))) ]
    (is (< current result (+ current (Math/toRadians 2.0)))
        "Yaw crosses the seam by two degrees, never turns 358 degrees")))
