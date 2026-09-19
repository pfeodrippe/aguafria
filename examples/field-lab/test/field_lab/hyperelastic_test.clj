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

(defn decimal-energy
  "Independent original material formula, evaluated at 80 digits from exact f64 inputs."
  [columns coefficients]
  (binding [*math-context* (java.math.MathContext. 80)]
    (let [decimal #(java.math.BigDecimal. (double %))
          f (mapv #(mapv decimal %) columns)
          cross (fn [[ax ay az] [bx by bz]]
                  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
          invariant (reduce + (map #(* % %) (mapcat clojure.core/identity f)))
          jacobian (reduce + (map * (f 0) (cross (f 1) (f 2))))
          mu (decimal (:mu coefficients))
          lambda (decimal (:lambda coefficients))
          alpha (decimal (:alpha coefficients))
          ;; log(x) = 2 atanh((x-1)/(x+1)); near rest twenty terms are ample.
          ratio (/ (- invariant 3M) (+ invariant 5M))
          logarithm (* 2M (loop [index 0 term ratio sum 0M]
                            (if (= index 20)
                              sum
                              (recur (inc index) (* term ratio ratio)
                                     (+ sum (/ term (bigdec (inc (* 2 index)))))))))
          rest (- 1M alpha)
          volume (- jacobian alpha)]
      (double (/ (- (+ (* mu (- invariant 3M))
                        (* lambda (- (* volume volume) (* rest rest))))
                     (* mu logarithm)) 2M)))))

(deftest small-strain-energy-retains-precision-after-rotation
  (let [rotate (fn [[x y z]]
                 [(- (* (Math/cos 0.7) x) (* (Math/sin 0.7) y))
                  (+ (* (Math/sin 0.7) x) (* (Math/cos 0.7) y)) z])]
    (doseq [poisson [0.0 0.3]
            strain [1e-4 1e-6 1e-8 1e-10 1e-12]
            columns [(assoc-in identity-columns [0 0] (+ 1.0 strain))
                     (assoc-in identity-columns [1 0] strain)]
            rotated? [false true]]
      (let [columns (if rotated? (mapv rotate columns) columns)
            parameters (material/material 1e6 poisson)
            expected (decimal-energy columns (az/value parameters))
            actual (:energy-density (az/value (material/evaluate (matrix columns) parameters)))]
        (is (<= (abs (- actual expected)) (max 1e-23 (* 1e-9 (abs expected))))
            (pr-str {:poisson poisson :strain strain :rotated? rotated?
                     :expected expected :actual actual}))))))

(deftest compensated-energy-gradient-matches-pk1
  (let [columns [[1.02 0.003 0.0] [0.001 0.98 0.002] [0.0 0.002 1.01]]
        stress (entries (:pk1 (response columns)))
        step 1e-6]
    (doseq [column (range 3) axis (range 3)]
      (let [plus (:energy-density (response (update-in columns [column axis] + step)))
            minus (:energy-density (response (update-in columns [column axis] - step)))]
        (is (< (abs (- (/ (- plus minus) (* 2 step)) (stress (+ (* 3 column) axis)))) 1e-4))))))

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
          ;; The implicit path must preserve exactly the same energy and force
          ;; assembly while omitting explicit stability bounds and telemetry.
          (let [optimized (az/value (dynamics/elastic-objective! state true))
                optimized-forces (mapv #(job/vector-data (dynamics/particle-force state %))
                                       (range (count points)))
                energy-only (az/value (dynamics/elastic-objective! state false))]
            (is (= (select-keys observation [:elastic-energy :minimum-jacobian]) optimized))
            (is (= forces optimized-forces))
            (is (= optimized energy-only))
            (is (= forces (mapv #(job/vector-data (dynamics/particle-force state %))
                                (range (count points))))))
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

(deftest displacement-gradient-preserves-tiny-dilation
  ;; Exactly representable parameters have zero rest stress. Their physical
  ;; Lame constants are mu=3000, lambda=5500 Pa, hence W=33750*epsilon^2.
  (let [parameters {:mu 4000.0 :lambda 8000.0 :alpha 1.375}]
    (doseq [epsilon [1e-12 1e-10 1e-8]]
      (let [h (matrix [[epsilon 0.0 0.0] [0.0 epsilon 0.0] [0.0 0.0 epsilon]])
            result (az/value (material/evaluate-gradient h parameters))]
        (is (< (abs (- (/ (:energy-density result) (* 33750.0 epsilon epsilon)) 1.0)) 1e-7))
        (doseq [[column axis] [[:c0 :x] [:c1 :y] [:c2 :z]]]
          (is (< (abs (- (/ (get-in result [:pk1 column axis]) (* 22500.0 epsilon)) 1.0)) 1e-7)))))))

(deftest displacement-gradient-agrees-with-general-constitutive-response
  ;; Include shear, compression, and both sides of the near-identity fallback.
  (let [parameters (material/material 100000.0 0.4)]
    (doseq [scale [1e-4 0.1 0.249 0.251 0.5 -0.2]]
      (let [h [[scale 0.0 0.0] [(* 0.1 scale) 0.0 0.0] [0.0 0.0 0.0]]
            f (mapv #(mapv + %1 %2) identity-columns h)
            direct (az/value (material/evaluate-gradient (matrix h) parameters))
            general (az/value (material/evaluate (matrix f) parameters))]
        (is (< (abs (- (:energy-density direct) (:energy-density general))) 1e-9))
        (is (< (difference (entries (:pk1 direct)) (entries (:pk1 general))) 1e-9))
        (is (< (abs (- (:jacobian direct) (:jacobian general))) 1e-14))))))
