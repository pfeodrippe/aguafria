(ns field-lab.coupled-fem-test
  (:require [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.contact-mesh :as contact]
            [field-lab.coupled-fem :as coupled]
            [field-lab.coupled-job :as joint]))

(az/defstruct ImpulseCheck {:layout :extern}
  [[:normal-impulse :f64] [:tangent-impulse :f64]
   [:normal-speed :f64] [:tangent-speed :f64]
   [:momentum-error :f64] [:kinetic-before :f64] [:kinetic-after :f64]])

(az/defn impulse-probe!
  "Isolate one vertex/face impulse with unequal FEM lumped node masses."
  :- ImpulseCheck
  [[assembly [:* coupled/Assembly]]]
  (let [a (az/index (az/field assembly bodies) 0)
        b (az/index (az/field assembly bodies) 1)
        surface (az/field b surface)
        face (az/index (az/field surface faces) 0)
        normal (contact/unit-normal surface 0)
        axis (if (< (ak/abs (az/field normal x)) 0.8) (p/v 1.0 0.0 0.0) (p/v 0.0 1.0 0.0))
        cross (p/cross normal axis)
        tangent (p/scale cross (/ 1.0 (p/length cross)))
        ^:var center (p/v 0.0 0.0 0.0)]
    (dotimes [local 3]
      (set! center (p/add center (p/scale (az/index (az/field surface points) (az/index face local)) (/ 1.0 3.0)))))
    (let [point (p/add center (p/scale normal -1.0e-7))
          closest (contact/closest-point surface point)]
      (dynamics/set-particle! (az/field a state) 1 point
                               (p/add (p/scale normal -2.0) (p/scale tangent 4.0)))
      (let [before (coupled/observe! assembly)
            response (coupled/project-vertex! a b 1 false)
            after (coupled/observe! assembly)
            relative (coupled/relative-velocity a b 1 closest)
            normal-speed (p/dot relative normal)
            tangent-speed (p/length (p/add relative (p/scale normal (- normal-speed))))]
        (ImpulseCheck
         {:normal-impulse (az/field response normal-impulse)
          :tangent-impulse (az/field response tangent-impulse)
          :normal-speed normal-speed :tangent-speed tangent-speed
          :momentum-error (p/length (p/add (az/field after momentum) (p/scale (az/field before momentum) -1.0)))
          :kinetic-before (az/field before kinetic-energy) :kinetic-after (az/field after kinetic-energy)})))))

(defn norm [v]
  (Math/sqrt (reduce + (map #(* % %) (vals v)))))

(defn energy [observation]
  (+ (:elastic-energy observation) (:kinetic-energy observation) (:potential-energy observation)))

(deftest mass-weighted-coulomb-impulse
  (doseq [friction [0.0 0.3 3.0]]
    (joint/with-system!
      [(joint/sphere {:refinement 0 :center [-0.2 0.0 0.0] :density 900.0 :friction friction})
       (joint/sphere {:refinement 1 :center [0.2 0.0 0.0] :density 1300.0 :friction friction})]
      (fn [assembly _ _]
        (let [result (az/value (impulse-probe! assembly))]
          (is (pos? (:normal-impulse result)))
          (is (<= (:tangent-impulse result) (+ 1.0e-12 (* friction (:normal-impulse result)))))
          (is (< (abs (:normal-speed result)) 1.0e-10))
          (is (< (abs (- (max 0.0 (- 4.0 (* friction 2.0))) (:tangent-speed result))) 1.0e-10))
          (is (< (:momentum-error result) 1.0e-12))
          (is (<= (:kinetic-after result) (:kinetic-before result))))))))

(deftest common-clock-free-flight
  (let [descriptions [(joint/sphere {:refinement 0 :center [-0.2 1.0 0.0] :velocity [0.1 0.2 -0.3]
                                    :gravity [0.0 -9.81 0.0]})
                      (joint/sphere {:refinement 1 :center [0.2 1.0 0.0] :velocity [-0.1 0.3 0.2]
                                    :gravity [0.0 -9.81 0.0]})]
        independent (mapv (fn [description]
                            (job/with-state! description
                              (fn [state _]
                                (job/advance! state 0.01 0.000025)
                                (az/value (dynamics/evaluate! state))))) descriptions)]
    (joint/with-system! descriptions
      (fn [assembly states _]
        (let [report (joint/advance! assembly 0.01 0.000025)]
          (is (= 0.01 (:time report)))
          (is (zero? (:pair-impulse report)))
          (is (zero? (:rejected report)))
          (doseq [[state expected] (map vector states independent)]
            (let [actual (az/value (dynamics/evaluate! state))]
              (is (< (norm (merge-with - (:center actual) (:center expected))) 1.0e-12))
              (is (< (norm (merge-with - (:momentum actual) (:momentum expected))) 1.0e-12)))))))))

(deftest invalid-jacobian-retains-all-body-state
  (let [description (joint/sphere {:refinement 0})
        compressed (assoc description :initial-positions (mapv #(mapv (partial * 0.3) %) (get-in description [:mesh :points])))]
    (joint/with-system! [compressed]
      (fn [assembly [state] _]
        (let [before (job/snapshot state 43)
              report (az/value (coupled/advance! assembly 0.001 0.000025))
              after (job/snapshot state 43)]
          (is (false? (:completed report)))
          (is (zero? (:time report)))
          (is (= before after)))))))

(deftest refined-three-body-collision
  (let [{:keys [history reports]} (joint/head-on-study! {:bodies 3 :refinement 1 :seconds 0.15 :maximum-step 0.000025})
        initial (:total (first history))
        final (last history)
        final-velocities (mapv #(/ (get-in % [:momentum :x]) (:mass %)) (:bodies final))]
    (is (every? :completed reports))
    (is (pos? (reduce + (map :pair-impulse reports))))
    (is (every? #(<= (:maximum-penetration %) 1.0e-9) reports))
    (is (every? #(> (:minimum-jacobian %) 0.05) reports))
    (is (> (apply max (map #(get-in % [:total :elastic-energy]) history)) (* 0.1 (energy initial))))
    (is (every? #(< (norm (merge-with - (get-in % [:total :momentum]) (:momentum initial))) 1.0e-9) history))
    (is (every? #(<= (energy (:total %)) (* 1.001 (energy initial))) history))
    (is (neg? (first final-velocities)))
    (is (pos? (last final-velocities)))
    (is (every? pos? (:x-gaps final)))))
