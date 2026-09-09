(ns racing-game.turnaround-query-test
  "Small native wall-query checks, without loading a whole circuit fixture."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-turnaround :as turnaround]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(az/defn static-query-probe :- [:array 3 :bool] []
  (let [world (physics/create-world 0.0)
        box (physics/create-box world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.0})
              (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0}) 0.0 10.0)]
    (ak/defer (physics/destroy-world! world))
    (b3/b3Body_SetType box b3/b3_staticBody)
    (let [blocked (turnaround/static-clearance world 0.0 0.0 0.0 0.0)
          clear (turnaround/static-clearance world 10.0 0.0 0.0 0.0)]
      ;; Dynamic objects are handled by the separate oriented/swept tests.
      (b3/b3Body_SetType box b3/b3_dynamicBody)
      (az/array-init [:array 3 :bool]
        [blocked clear (turnaround/static-clearance world 0.0 0.0 0.0 0.0)]))))

(deftest static-world-query-rejects-obstacles-test
  (is (= [false true true] (az/value (static-query-probe)))))

(az/defn corridor-query-probe
  "Read-only motion prediction in an isolated empty world. Only tests the
  fixed recovery corridor; it is not a full physical recovery simulation."
  :- :u8 [[body physics/BodyState] [gear :i8] [steering :f32] [limit :f32]]
  (let [world (physics/create-world 0.0)
        others (mem/zeroes (az/type [:array protocol/racer-count physics/BodyState]))]
    (ak/defer (physics/destroy-world! world))
    (turnaround/motion-clearance-reasons body others 0 0 gear steering world limit)))

(def displaced-r0-pose
  "Measured September 8 R0, outside its original 7.2m recovery corridor.
  A query fixture, never applied as a transform to a live car."
  {:x -534.23303 :y 110.11755 :z 43.31183
   :vx 0.0 :vy 0.0 :vz 0.0 :wx 0.0 :wy 0.0 :wz 0.0
   :qx 0.007192995 :qy 0.038945414 :qz -0.43960717 :qw 0.89731663})

(deftest recovery-corridor-stays-fixed-while-allowing-inward-progress-test
  (doseq [sign [-1.0 1.0]
          [current predicted allowed?] [[6.0 7.0 true] [7.1 7.21 false]
                                        [7.2 7.2 true] [7.3 7.2 true]
                                        [12.7 12.6 true] [12.7 12.7 false]
                                        [12.7 12.8 false] [7.3 7.2995 false]]]
    (is (= allowed? (turnaround/corridor-step-safe? (* sign current) (* sign predicted) 7.2))
        (str sign " x " current " -> " predicted)))
  (doseq [[current predicted limit] [[Float/NaN 0.0 7.2] [0.0 Float/NaN 7.2]
                                     [Float/POSITIVE_INFINITY 0.0 7.2]
                                     [0.0 0.0 Float/POSITIVE_INFINITY]
                                     [0.0 0.0 -1.0]]]
    (is (false? (turnaround/corridor-step-safe? current predicted limit)))))

(deftest displaced-recovery-can-query-an-inward-arc-test
  (let [body (physics/BodyState displaced-r0-pose)
        reasons (mapv (fn [[gear steering]] (corridor-query-probe body gear steering 7.2))
                      [[1 0.45] [-1 -0.45] [1 -0.45] [-1 0.45]])]
    (is (some #(zero? (bit-and % 2)) reasons)
        (str "An inward arc must not be trapped outside the original corridor: " reasons))
    (is (some #(pos? (bit-and % 2)) reasons)
        "Outward arcs must still be rejected; do not expand the corridor")))

(defn test-body [x y yaw]
  (physics/BodyState {:x x :y y :z 0.76 :vx 0.0 :vy 0.0 :vz 0.0
                      :qx 0.0 :qy 0.0 :qz (Math/sin (/ yaw 2.0))
                      :qw (Math/cos (/ yaw 2.0)) :wx 0.0 :wy 0.0 :wz 0.0}))

(deftest parallel-clearance-is-not-an-obstruction-test
  (doseq [[current predicted expected]
          [[0.194 0.194 true] [0.1 0.1001 true] [0.1 0.09 false]
           [0.1 -0.001 false] [0.0005 -0.0001 false]
           [-0.1 -0.1 false] [-0.1 -0.11 false] [-0.1 -0.09 true]
           [-0.1 0.01 true] [0.5 0.3 true]]]
    (is (= expected (turnaround/separation-safe? current predicted))
        (str "Clearance " current " -> " predicted))))

(deftest passing-clearance-includes-both-oriented-footprints-test
  (doseq [[yaw other-yaw expected] [[0.0 0.0 3.24]
                                   [0.0 Math/PI 3.24]
                                   [(/ Math/PI 2.0) 0.0 4.32]]]
    (is (< (Math/abs (- expected
                        (turnaround/recovery-side-clearance
                          (test-body 0.0 0.0 yaw) (test-body 0.0 0.0 other-yaw) 0.0)))
           0.00001))))

(deftest requested-steering-predicts-forward-and-reverse-yaw-test
  (doseq [steering [-0.45 -0.16 0.0 0.16 0.45]
          distance [-0.35 0.35]]
    (let [[x y yaw] (az/value (turnaround/motion-pose (test-body 0.0 0.0 0.0)
                                                   steering distance))]
      (is (pos? (* distance x)) "Reverse must actually predict backwards travel")
      (if (zero? steering)
        (do (is (< (Math/abs (double (- x distance))) 0.00001))
            (is (zero? y)) (is (zero? yaw)))
        (do (is (pos? (* distance steering yaw))
                "The same steering produces opposite yaw changes in reverse")
            (is (pos? (* steering y))))))))

(az/defn captured-pedal-guard-probe
  "Pure measured-pose query in an empty Box3D world: isolate dynamic footprint
  vetoes from ground/wall queries. This does NOT simulate a cleared pile-up."
  :- driver/Control
  [[bodies [:array protocol/racer-count physics/BodyState]] [self :usize] [control driver/Control] [gear :i8]]
  (let [world (physics/create-world 0.0)]
    (ak/defer (physics/destroy-world! world))
    (turnaround/guard-recovery-control control (az/index bodies self)
      bodies 8 self gear world 35.0)))

(deftest captured-pileup-does-not-allow-r0-to-push-through-test
  (let [capture (edn/read-string (slurp (io/resource "fixtures/blocked-grid-2026-09-08.edn")))
        racers (:racers capture)
        bodies (mapv #(first (:poses %)) racers)
        {:keys [control gear]} (:recovery (first racers))
        padded (into bodies (repeat (- (az/value protocol/racer-count) (count bodies)) (first bodies)))
        guarded (az/value (captured-pedal-guard-probe padded 0 (driver/Control control) gear))
        ;; Counterfactual QUERY inputs only: no real race transforms are changed.
        empty-corridor (mapv (fn [i body] (if (zero? i) body (update body :x + 100.0)))
                            (range (az/value protocol/racer-count)) padded)
        clear (az/value (captured-pedal-guard-probe empty-corridor 0 (driver/Control control) gear))]
    (is (= 8 (count bodies)))
    (is (pos? (:throttle control)) "Reproduce the real captured unsafe request")
    (is (zero? (:throttle guarded)) "Reject forward motion into the neighbouring footprint")
    (is (= 1.0 (:brake guarded)))
    (is (= (select-keys (az/value (driver/Control control)) [:steering :progress :lane :speed])
           (select-keys guarded [:steering :progress :lane :speed]))
        "Safety must not rewrite the model's destination or measured state")
    (is (= (az/value (driver/Control control)) clear)
        "An empty forward corridor must not acquire an artificial brake")))
