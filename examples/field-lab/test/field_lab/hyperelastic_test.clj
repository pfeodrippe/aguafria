(ns field-lab.hyperelastic-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [field-lab.hyperelastic :as material]
            [field-lab.nonlinear-job :as job]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.fem-job :as linear]))

(defn matrix [columns]
  (zipmap [:c0 :c1 :c2] (map job/vector-map columns)))

(defn entries [native]
  (let [value (az/value native)]
    (vec (for [column [:c0 :c1 :c2] axis [:x :y :z]] (get-in value [column axis])))))

(defn response [columns]
  (az/value (material/evaluate (matrix columns) (material/material 100000.0 0.4))))

(defn difference [actual expected]
  (apply max (map #(abs (- %1 %2)) actual expected)))

(def identity-columns [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])

(deftest energy-gradient-and-tangent
  (let [columns [[1.1 0.03 -0.01] [0.1 0.85 0.04] [0.02 -0.03 1.02]]
        parameters (material/material 100000.0 0.4)
        h 1.0e-6
        analytic (entries (:pk1 (response columns)))]
    (doseq [column (range 3) row (range 3)]
      (let [plus (update-in columns [column row] + h)
            minus (update-in columns [column row] - h)
            numerical (/ (- (:energy-density (response plus)) (:energy-density (response minus))) (* 2.0 h))
            direction (assoc-in [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]] [column row] 1.0)
            tangent (entries (material/tangent (matrix columns) (matrix direction) parameters))
            numerical-tangent (mapv #(/ (- %1 %2) (* 2.0 h))
                                    (entries (:pk1 (response plus))) (entries (:pk1 (response minus))))]
        (is (< (abs (- numerical (analytic (+ (* 3 column) row)))) 1.0e-4))
        (is (< (difference tangent numerical-tangent) 1.0e-3))))))

(deftest rotation-objectivity-and-linear-limit
  (let [rotation (fn [[x y z]] [(- y) x z])
        columns [[1.1 0.0 0.0] [0.2 0.9 0.0] [0.0 0.0 1.05]]
        original (response columns)
        rotated (response (mapv rotation columns))
        rotate-stress (mapcat #(rotation (mapv (get (:pk1 original) %) [:x :y :z])) [:c0 :c1 :c2])
        rest (response identity-columns)
        rigid (response (mapv rotation identity-columns))
        strain [[0.002 -0.001 0.0] [0.003 -0.0006 0.0] [0.0 0.0 -0.0006]]
        tangent (entries (material/tangent (matrix identity-columns) (matrix strain)
                                            (material/material 2.0e6 0.3)))
        shear (/ 2.0e6 2.6)]
    (is (< (abs (:energy-density rest)) 1.0e-10))
    (is (< (abs (:energy-density rigid)) 1.0e-10))
    (is (< (apply max (map abs (entries (:pk1 rigid)))) 1.0e-9))
    (is (< (abs (- (:energy-density original) (:energy-density rotated))) 1.0e-9))
    (is (< (difference (entries (:pk1 rotated)) rotate-stress) 1.0e-8))
    (is (< (difference tangent [4000.0 (* shear 0.002) 0.0 (* shear 0.002) 0.0 0.0 0.0 0.0 0.0]) 1.0e-8))))

(deftest inverted-and-collapsed-material-evaluation-is-finite
  (doseq [columns [[[-0.5 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
                   [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]]
                   [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 0.0]]]]
    (let [result (response columns)]
      (is (every? #(Double/isFinite (double %))
                   (cons (:energy-density result) (entries (:pk1 result))))))))

(deftest volumetric-refinement-preserves-the-domain
  (let [meshes (take 3 (iterate job/refine (job/sphere-mesh 0.05 [0.0 0.55 0.0])))
        volumes (mapv (fn [{:keys [points cells]}]
                        (reduce + (map #(:volume (linear/tetrahedron points %)) cells))) meshes)]
    (is (= [80 640 5120] (mapv #(count (:cells %)) meshes)))
    (is (< (- (apply max volumes) (apply min volumes)) 1.0e-15))
    (is (= [80 320 1280] (mapv #(count (linear/boundary-faces %)) meshes)))))

(deftest native-assembly-conserves-internal-force-and-torque
  (let [description (job/solid-ball)
        points (get-in description [:mesh :points])
        deformed (mapv (fn [[x y z]] [(+ (* 1.08 x) (* 0.05 y)) (* 0.94 y) (* 1.02 z)]) points)]
    (job/with-state! (assoc description :initial-positions deformed :floor? false)
      (fn [state _]
        (let [observation (az/value (dynamics/evaluate! state))
              forces (mapv #(job/vector-data (dynamics/particle-force state %)) (range (count points)))
              total (apply mapv + forces)
              torque (apply mapv + (map linear/cross deformed forces))]
          (is (pos? (:elastic-energy observation)))
          (is (< (apply max (map abs total)) 1.0e-9))
          (is (< (apply max (map abs torque)) 1.0e-9))
          (let [h 1.0e-7
                node 12
                original (deformed node)
                plus (update original 0 + h)
                minus (update original 0 - h)]
            (dynamics/set-particle! state node (job/vector-map plus) (job/vector-map [0.0 0.0 0.0]))
            (let [upper (:elastic-energy (az/value (dynamics/evaluate! state)))]
              (dynamics/set-particle! state node (job/vector-map minus) (job/vector-map [0.0 0.0 0.0]))
              (let [lower (:elastic-energy (az/value (dynamics/evaluate! state)))]
                (is (< (abs (+ (get-in forces [node 0]) (/ (- upper lower) (* 2.0 h)))) 1.0e-5))))))))))

(deftest free-fall-is-ballistic-and-does-not-strain-the-solid
  (let [description (assoc (job/solid-ball) :floor? false)]
    (job/with-state! description
      (fn [state _]
        (let [initial (:observables (job/snapshot state 43))
              report (job/advance! state 0.1 0.0001)
              result (job/snapshot state 43)]
          (is (:completed report))
          (is (< (abs (- (get-in result [:observables :center :y])
                          (- (get-in initial [:center :y]) (* 0.5 9.81 0.01)))) 1.0e-10))
          (is (< (apply max (map #(abs (+ (second %) 0.981)) (:velocities-m-s result))) 1.0e-8))
          (is (< (abs (- (get-in result [:observables :minimum-jacobian]) 1.0)) 1.0e-9))
          (is (< (abs (get-in result [:observables :elastic-energy])) 1.0e-8)))))))

(defn oscillation [step]
  (let [description (assoc (job/solid-ball) :gravity [0.0 0.0 0.0] :floor? false)
        points (get-in description [:mesh :points])
        displaced (mapv (fn [[x y z]] [(* 1.04 x) y z]) points)]
    (job/with-state! (assoc description :initial-positions displaced)
      (fn [state _]
        (let [initial (:observables (job/snapshot state 43))
              report (job/advance! state 0.012 step)
              result (job/snapshot state 43)]
          {:report report :initial-energy (:elastic-energy initial)
           :final-energy (+ (get-in result [:observables :elastic-energy])
                             (get-in result [:observables :kinetic-energy]))
           :positions (:positions-m result)})))))

(deftest temporal-convergence-and-free-elastic-energy
  (let [results (mapv oscillation [0.00001 0.000005 0.0000025])
        distance (fn [a b] (Math/sqrt (reduce + (map #(Math/pow (- %1 %2) 2.0)
                                                                   (flatten (:positions a))
                                                                   (flatten (:positions b))))))
        coarse (distance (results 0) (results 1))
        fine (distance (results 1) (results 2))]
    (is (every? #(get-in % [:report :completed]) results))
    (is (< 3.5 (/ coarse fine) 4.5) (pr-str {:coarse coarse :fine fine}))
    (doseq [result results]
      (is (< (abs (/ (- (:final-energy result) (:initial-energy result)) (:initial-energy result))) 0.01)))))
