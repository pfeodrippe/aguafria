(ns field-lab.contacts-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [field-lab.physics :as p]
            [field-lab.contacts :as contacts]
            [field-lab.experiments :as experiments]))

(defn advance [sample config count dt] (az/value (contacts/advance sample config count dt)))

(defn momentum
  [sample config]
  (mapv (fn [axis]
          (* (:mass config) (reduce + (map #(get-in % [:velocity axis]) (:bodies sample)))))
        [:x :y :z]))

(defn energy [sample config] (reduce + (map #(p/energy % config) (:bodies sample))))

(deftest equal-mass-head-on-collision
  (let [config (assoc (az/value (p/defaults))
                      :gravity 0.0
                      :restitution 1.0
                      :friction 0.0
                      :rolling 0.0
                      :spin 0.0)
        base (az/value (p/initial config))
        a (assoc base
                 :position {:x -0.451 :y 2.0 :z 0.0}
                 :velocity {:x 1.0 :y 0.0 :z 0.0})
        b (assoc base
                 :position {:x 0.451 :y 2.0 :z 0.0}
                 :velocity {:x -1.0 :y 0.0 :z 0.0})
        sample {:bodies [a b base]}
        result (advance sample config 2 (/ 1.0 240))]
    (is (< (abs (+ 1.0 (get-in result [:bodies 0 :velocity :x]))) 1.0e-10))
    (is (< (abs (- 1.0 (get-in result [:bodies 1 :velocity :x]))) 1.0e-10))
    (is (< (abs (- (energy sample config) (energy result config))) 1.0e-10))))

(deftest three-colliding-balls-conserve-momentum
  (let [config (assoc (az/value (p/defaults))
                      :gravity 0.0
                      :restitution 1.0
                      :friction 0.0
                      :rolling 0.0)
        initial (az/value (experiments/three-balls config))
        result (nth (iterate #(advance % config 3 (/ 1.0 240)) initial) 480)]
    (is (> (reduce + (map :impacts (:bodies result))) 0))
    (is (every? #(< (abs %) 1.0e-9) (map - (momentum initial config) (momentum result config))))
    (is (< (abs (- (energy initial config) (energy result config))) 1.0e-8))))

(deftest dissipative-three-ball-run
  (let [config (az/value (p/defaults))
        initial (az/value (experiments/three-balls config))
        states (take 721 (iterate #(advance % config 3 (/ 1.0 240)) initial))
        result (last states)]
    (is (< (energy result config) (energy initial config)))
    (is (> (reduce + (map :impacts (:bodies result))) 3))
    (is (every? #(>= (get-in % [:position :y]) (- (:radius config) 1.0e-9))
                (mapcat :bodies states)))
    (is (every? (fn [sample]
                  (every? (fn [[a b]]
                            (let [pa (:position (get-in sample [:bodies a]))
                                  pb (:position (get-in sample [:bodies b]))
                                  distance (Math/sqrt
                                            (reduce +
                                                    (map (fn [k] (let [d (- (k pa) (k pb))] (* d d)))
                                                         [:x :y :z])))]
                              (>= distance (- (* 2 (:radius config)) 1.0e-5))))
                          [[0 1] [0 2] [1 2]]))
                states))))
