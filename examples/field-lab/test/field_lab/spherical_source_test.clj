(ns field-lab.spherical-source-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as physics]
            [field-lab.mesh-cache :as cache]
            [field-lab.coupled-job :as joint]
            [field-lab.nonlinear-job :as job]
            [field-lab.nonlinear-fem :as dynamics]
            [pitoco.geometry :as geometry]))

(az/defn cached-reference-volume
  :- :f64
  [[owned [:* cache/Cache]]]
  (let [points (az/field owned reference)
        ^{:var :f64} result 0.0]
    (dotimes [index (az/field (az/field owned cells) len)]
      (let [cell (az/index (az/field owned cells) index)
            origin (az/index points (az/index cell 0))
            a (physics/add (az/index points (az/index cell 1)) (physics/scale origin -1.0))
            b (physics/add (az/index points (az/index cell 2)) (physics/scale origin -1.0))
            c (physics/add (az/index points (az/index cell 3)) (physics/scale origin -1.0))]
        (set! result (+ result (/ (ak/abs (physics/dot a (physics/cross b c))) 6.0)))))
    result))

(deftest mass-is-preserved-as-physical-boundary-refines
  (doseq [level [0 1 2]]
    (let [result (job/bake-cache! {:refinement level :seconds (/ 1.0 240.0)
                                 :config {:gravity 0.0 :vx 0.0 :vz 0.0 :spin 0.0}})
          owned (:cache result)]
      (try
        (let [initial (:observation (az/value (cache/frame-info owned 0)))
              final (:observation (az/value (cache/frame-info owned 1)))
              mesh (get-in result [:job :mesh])]
          (is (= :sphere (get-in mesh [:source :geometry])))
          (is (< (abs (- 20.0 (:mass initial))) 3.0e-12))
          (is (< (abs (- 20.0 (:mass final))) 3.0e-12))
          (is (< (abs (- (cached-reference-volume owned) (get-in mesh [:metrics :volume]))) 1.0e-12))
          (is (< (abs (:elastic-energy initial)) 1.0e-9))
          (is (< (:kinetic-energy final) 1.0e-15))
          (is (< (abs (- 1.0 (:minimum-jacobian final))) 1.0e-12)))
        (finally (cache/destroy! owned))))))

(deftest curved-boundary-is-used-by-the-native-solver
  (let [description (joint/sphere {:radius 0.05 :refinement 2 :velocity [0.2 -0.3 0.4]
                                    :density 1100.0 :gravity [0.0 0.0 0.0]})
        mesh (:mesh description)
        boundary (set (mapcat identity (geometry/boundary-faces mesh)))]
    (job/with-state! description
      (fn [state _]
        (let [initial (az/value (dynamics/evaluate! state))]
          (is (< (abs (- (:mass initial) (* 1100.0 (get-in mesh [:metrics :volume])))) 1.0e-12))
          (is (every? #(< (abs (- 0.05 (geometry/length ((:points mesh) %)))) 1.0e-14) boundary))
          (job/advance! state 0.01 0.000025)
          (let [final (az/value (dynamics/evaluate! state))
                expected [0.002 -0.003 0.004]]
            (is (every? true? (map #(< (abs (- %1 %2)) 1.0e-12)
                                  expected (map (:center final) [:x :y :z]))))
            (is (< (abs (- (:kinetic-energy final) (:kinetic-energy initial))) 1.0e-12))
            (is (> (:minimum-jacobian final) 0.999999999))
            (is (every? (fn [index]
                          (let [position (job/vector-data (dynamics/position state index))
                                reference ((:points mesh) index)]
                            (every? #(< (abs %) 1.0e-12) (map - position (map + reference expected)))))
                        boundary))))))))
