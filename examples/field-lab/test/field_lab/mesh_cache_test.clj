(ns field-lab.mesh-cache-test
  (:require [pitoco.geometry :as geometry]
            [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria-examples-native.mesh :as gpu]
            [field-lab.fem :as fem]
            [field-lab.physics :as p]
            [field-lab.mesh-cache :as cache]
            [field-lab.nonlinear-job :as job]
            [field-lab.fem-job :as linear]
            [field-lab.scene :as scene]
            [field-lab.surface :as surface]))

(az/defstruct Emission {:layout :extern}
  [[:vertices :u32] [:minimum-normal-length :f64] [:maximum-normal-length :f64]])

(az/defn measure-emission!
  :- Emission
  []
  (let [storage (fem/allocate gpu/GpuVertex 8192)]
    (defer ((az/field heap/page_allocator free) storage))
    (let [written (surface/emit! (az/field storage ptr) 0.55 0.3 9.0)
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
