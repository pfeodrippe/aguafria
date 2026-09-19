(ns field-lab.mixed-tetra-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [field-lab.physics :as p]
            [field-lab.mixed-tetra :as mixed]
            [field-lab.hyperelastic :as elastic]))

(defn vec3 [values] (zipmap [:x :y :z] values))

(defn vector-data [value] (mapv value [:x :y :z]))

(def reference [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])

(def gradients (mapv vec3 [[-1.0 -1.0 -1.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]))

(def zero-displacement (vec (repeat 8 (vec3 [0.0 0.0 0.0]))))

(defn maximum [values] (apply max (map abs values)))

(defn evaluate [displacements order hessian?]
  (az/value (mixed/evaluate gradients (/ 1.0 6.0) (elastic/material 10000.0 0.3)
                            displacements (mixed/quadrature order) hessian?)))

(defn quadrature-samples [order]
  (let [{:keys [count nodes weights]} (az/value (mixed/quadrature order))]
    (for [i (range count) j (range count) k (range count)
          :let [u (nodes i) v (nodes j) w (nodes k) a (- 1.0 u) b (- 1.0 v)]]
      {:barycentric [(* a b (- 1.0 w)) u (* a v) (* a b w)]
       :weight (* a a b (weights i) (weights j) (weights k))})))

(deftest positive-quadrature-integrates-polynomials
  (doseq [order [2 3 4 6 8 10 12]]
    (let [{:keys [count nodes weights]} (az/value (mixed/quadrature order))]
      (is (= order count))
      (is (every? pos? (take count weights)))
      (doseq [degree (range (* 2 order))]
        (is (< (abs (- (/ 1.0 (inc degree))
                       (reduce + (map #(* %1 (Math/pow %2 degree))
                                      (take count weights) (take count nodes))))) 2e-14)))))
  (doseq [order [0 1 13]]
    (is (zero? (:count (az/value (mixed/quadrature order)))))))

(deftest boundary-trace-and-linear-moment-compatibility
  (doseq [coordinates [[1.0 0.0 0.0 0.0] [0.0 0.2 0.3 0.5]
                       [0.2 0.0 0.5 0.3] [0.2 0.3 0.0 0.5] [0.2 0.3 0.5 0.0]
                       [0.25 0.25 0.25 0.25] [0.1 0.2 0.3 0.4]]]
    (let [{:keys [values gradients]} (az/value (mixed/basis coordinates gradients))]
      (is (< (abs (- 1.0 (reduce + values))) 2e-14))
      (is (< (maximum (reduce #(mapv + %1 (vector-data %2)) [0.0 0.0 0.0] gradients)) 2e-13))
      (when (some zero? coordinates)
        (is (= coordinates (subvec values 0 4)))
        (is (every? zero? (subvec values 4 8))))))
  (let [samples (mapv #(assoc % :basis (az/value (mixed/basis (:barycentric %) gradients)))
                      (quadrature-samples 6))]
    ;; Independent target: integral l_i*l_j = (1+delta_ij)/120 on the unit tet.
    (doseq [row (range 4) column (range 4)]
      (let [moment (fn [index]
                     (reduce + (map #(* (:weight %) (get (:barycentric %) row)
                                         (get-in % [:basis :values index])) samples)))
            expected (/ (if (= row column) 2.0 1.0) 120.0)]
        (is (< (abs (moment column)) 2e-16))
        (is (< (abs (- expected (moment (+ column 4)))) 2e-16))))))

(deftest basis-gradients-are-derivatives
  (let [point [0.2 0.25 0.15]
        barycentric (fn [[x y z]] [(- 1.0 x y z) x y z])
        sample (az/value (mixed/basis (barycentric point) gradients))
        h 1e-6]
    (doseq [axis (range 3)]
      (let [plus (:values (az/value (mixed/basis (barycentric (update point axis + h)) gradients)))
            minus (:values (az/value (mixed/basis (barycentric (update point axis - h)) gradients)))]
        (is (< (maximum (map-indexed
                          (fn [index value]
                            (- (/ (- value (minus index)) (* 2 h))
                               (get-in sample [:gradients index ([:x :y :z] axis)]))) plus)) 2e-8))))))

(deftest affine-patches-and-rigid-motion-inertia
  (doseq [mapping [(fn [[x y z]] [(+ x 3.0) (- y 2.0) (+ z 1.0)])
                   (fn [[x y z]] [(- y) x z])
                   (fn [[x y z]] [(+ (* 1.1 x) (* 0.2 y)) (* 0.9 y) (* 1.05 z)])]]
    (let [moved (mapv mapping reference)
          displacements (mapv #(vec3 (mapv - %1 %2)) moved reference)
          original (moved 0)
          columns (mapv #(vec3 (mapv - % original)) (subvec moved 1 4))
          expected (:energy-density
                     (az/value (elastic/evaluate (zipmap [:c0 :c1 :c2] columns)
                                                 (elastic/material 10000.0 0.3))))
          result (evaluate (into displacements displacements) 6 true)]
      (is (:valid result))
      (is (< (abs (- (:energy result) (/ expected 6.0))) 1e-9))
      (is (< (maximum (map #(reduce + (take-nth 3 (drop % (:gradient result)))) (range 3))) 1e-9))))
  (let [velocity (mapv (fn [[x y _]] (vec3 [(* -2.0 (- y 0.25)) (* 2.0 (- x 0.25)) 0.0])) reference)
        full (into velocity velocity)
        changed (into (vec (repeat 4 (vec3 [1e6 -1e6 1e6]))) velocity)]
    (is (< (abs (- 3.0 (mixed/kinetic-energy 120.0 (/ 1.0 6.0) full))) 1e-13))
    (is (= (mixed/kinetic-energy 120.0 (/ 1.0 6.0) full)
           (mixed/kinetic-energy 120.0 (/ 1.0 6.0) changed))))
  (doseq [row (range 8) column (range 8)]
    (is (= (if (and (>= row 4) (>= column 4)) (if (= row column) 2.0 1.0) 0.0)
           (mixed/mass-entry 120.0 (/ 1.0 6.0) row column)))))

(def nonaffine-displacement
  (mapv (fn [node] (vec3 (mapv #(* 0.003 (Math/sin (+ (* 0.7 node) %))) (range 3)))) (range 8)))

(az/defn benchmark-elements :f64
  "Repeated full nonlinear element evaluations with changing input. Return a
  checksum so the compiled benchmark cannot discard the tangent computation."
  [[rest-gradients [:array 4 p/Vec3]] [displacements [:array 8 p/Vec3]] [count :u32]]
  (let [^:var current displacements
        rule (mixed/quadrature 6)
        parameters (elastic/material 10000.0 0.3)
        ^{:var :f64} checksum 0.0]
    (dotimes [iteration count]
      (ak/+= (az/field (az/index current 0) x) 1e-8)
      (let [response (mixed/evaluate rest-gradients (/ 1.0 6.0) parameters current rule true)]
        (ak/+= checksum (az/index (az/field response hessian) (mod iteration 576)))))
    checksum))

(deftest nonlinear-energy-gradient-and-hessian
  (let [result (evaluate nonaffine-displacement 8 true)
        h 1e-6]
    (is (:valid result))
    (doseq [column (range 24)]
      (let [path [(quot column 3) ([:x :y :z] (mod column 3))]
            plus (evaluate (update-in nonaffine-displacement path + h) 8 false)
            minus (evaluate (update-in nonaffine-displacement path - h) 8 false)
            finite-gradient (/ (- (:energy plus) (:energy minus)) (* 2 h))
            finite-column (mapv #(/ (- %1 %2) (* 2 h)) (:gradient plus) (:gradient minus))
            exact-column (mapv #(get (:hessian result) (+ (* 24 %) column)) (range 24))]
        (is (< (abs (- finite-gradient (get (:gradient result) column))) 2e-6))
        (is (< (/ (maximum (map - finite-column exact-column)) (maximum exact-column)) 2e-8))))
    (is (< (maximum (for [i (range 24) j (range 24)]
                      (- (get (:hessian result) (+ (* 24 i) j))
                         (get (:hessian result) (+ (* 24 j) i))))) 1e-9))))

(deftest quadrature-refinement-and-rest-stiffness
  (let [six (evaluate zero-displacement 6 true)
        eight (evaluate zero-displacement 8 true)]
    (is (< (maximum (map - (:hessian six) (:hessian eight))) 1e-9))
    (doseq [axis (range 3)]
      (let [translation (vec (for [_ (range 8) component (range 3)] (if (= axis component) 1.0 0.0)))
            result (mapv #(reduce + (map * translation (subvec (:hessian six) (* % 24) (* (inc %) 24)))) (range 24))]
        (is (< (maximum result) 1e-9)))))
  (let [energies (mapv #(:energy (evaluate nonaffine-displacement % false)) [6 8 10])
        coarse (abs (- (energies 0) (energies 2)))
        fine (abs (- (energies 1) (energies 2)))]
    (is (< fine (max 1e-13 (* 0.05 coarse))))))

(deftest invalid-quadrature-volume-and-inversion-are-rejected
  (is (not (:valid (az/value (mixed/evaluate gradients 0.0 (elastic/material 10000.0 0.3)
                                             zero-displacement (mixed/quadrature 6) false)))))
  (is (not (:valid (evaluate zero-displacement 1 false))))
  (let [displacements (mapv (fn [[x _ _]] (vec3 [(* -2.0 x) 0.0 0.0])) reference)]
    (is (not (:valid (evaluate (into displacements displacements) 6 false))))))

(defn cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(deftest finite-deformation-objectivity-and-general-tetrahedron
  (let [original (evaluate nonaffine-displacement 8 false)
        positions (into reference reference)]
    (doseq [angle [0.7 1.5707963267948966]]
      (let [rotate (fn [[x y z]] [(- (* (Math/cos angle) x) (* (Math/sin angle) y))
                                 (+ (* (Math/sin angle) x) (* (Math/cos angle) y)) z])
            rotated (mapv (fn [x displacement]
                            (vec3 (mapv - (rotate (mapv + x (vector-data displacement))) x)))
                          positions nonaffine-displacement)
            result (evaluate rotated 8 false)
            expected-gradient (mapcat rotate (partition 3 (:gradient original)))]
        (is (:valid result))
        (is (< (abs (- (:energy original) (:energy result))) 1e-9))
        (is (< (maximum (map - expected-gradient (:gradient result))) 2e-9))))
    (let [torque (reduce (fn [sum [x displacement gradient]]
                           (mapv + sum (cross (mapv + x (vector-data displacement)) gradient)))
                         [0.0 0.0 0.0]
                         (map vector positions nonaffine-displacement (partition 3 (:gradient original))))]
      (is (< (maximum torque) 2e-9))))
  (let [a [2.0 0.2 0.0] b [0.3 1.5 0.1] c [0.1 0.25 0.9]
        determinant (reduce + (map * a (cross b c)))
        inverse-gradients (mapv #(mapv (fn [value] (/ value determinant)) %) [(cross b c) (cross c a) (cross a b)])
        origin-gradient (mapv - (reduce #(mapv + %1 %2) [0.0 0.0 0.0] inverse-gradients))
        world-gradients (mapv vec3 (into [origin-gradient] inverse-gradients))
        points [[0.0 0.0 0.0] a b c]
        displacement (mapv (fn [[x y z]] (vec3 [(+ 0.1 (* 0.1 x) (* 0.2 y)) (- 0.2 (* 0.1 y)) (* 0.05 z)])) points)
        parameters (elastic/material 10000.0 0.3)
        response (az/value (mixed/evaluate world-gradients (/ determinant 6.0) parameters
                                            (into displacement displacement) (mixed/quadrature 6) false))
        expected (:energy-density
                   (az/value (elastic/evaluate {:c0 (vec3 [1.1 0.0 0.0]) :c1 (vec3 [0.2 0.9 0.0])
                                                :c2 (vec3 [0.0 0.0 1.05])} parameters)))]
    (is (:valid response))
    (is (< (abs (- (:energy response) (* expected (/ determinant 6.0)))) 1e-9))))

(deftest whole-element-and-path-inversion-bound
  (let [rest (az/value (mixed/path-jacobian-bound gradients zero-displacement zero-displacement))
        small (mapv (fn [v] (into {} (map (fn [[axis value]] [axis (* 0.02 value)]) v))) nonaffine-displacement)
        bound (az/value (mixed/path-jacobian-bound gradients zero-displacement small))]
    (is (< 0.99999999999 (:lower rest) 1.0))
    (is (> (:upper rest) 1.0))
    (is (pos? (:lower bound)))
    (doseq [fraction [0.0 0.25 0.5 0.75 1.0]
            {:keys [barycentric]} (quadrature-samples 3)]
      (let [displacement (mapv (fn [v] (into {} (map (fn [[axis value]] [axis (* fraction value)]) v))) small)
            f (mixed/deformation (mixed/basis barycentric gradients) displacement)
            jacobian (elastic/determinant f)]
        (is (<= (:lower bound) jacobian (:upper bound))))))
  ;; A half-turn has positive endpoint Jacobians but its straight interpolation
  ;; collapses at the midpoint. Endpoint-only testing must not certify it.
  (let [half-turn (mapv (fn [[x y _]] (vec3 [(* -2.0 x) (* -2.0 y) 0.0])) reference)
        endpoint (into half-turn half-turn)
        bound (az/value (mixed/path-jacobian-bound gradients zero-displacement endpoint))]
    (is (:valid (evaluate endpoint 6 false)))
    (is (<= (:lower bound) 0.0 (:upper bound)))))

(deftest quadrature-can-miss-an-inverted-enriched-element
  ;; This witness was found independently from the polynomial derivative.
  ;; Every 6x6x6 Gauss point is positive while a face point has negative J.
  (let [displacement (assoc-in zero-displacement [5 :x] 0.058869298663277475)
        sampled (evaluate displacement 6 false)
        u 0.5310234280197912
        witness [0.0 u (* 0.5 (- 1.0 u)) (* 0.5 (- 1.0 u))]
        jacobian (elastic/determinant (mixed/deformation (mixed/basis witness gradients) displacement))
        bound (az/value (mixed/path-jacobian-bound gradients zero-displacement displacement))]
    (is (:valid sampled))
    (is (> (:minimum-jacobian sampled) 0.09))
    (is (< jacobian -0.09))
    (is (<= (:lower bound) jacobian (:upper bound)))))

(deftest nonfinite-inversion-inputs-remain-uncertified
  (doseq [value [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
    (let [after (assoc-in zero-displacement [4 :x] value)
          bound (az/value (mixed/path-jacobian-bound gradients zero-displacement after))]
      (is (= Double/NEGATIVE_INFINITY (:lower bound)))
      (is (= Double/POSITIVE_INFINITY (:upper bound))))))

(az/defstruct AssemblyProbe {:layout :extern}
  [[:response mixed/StepResponse] [:cached-energy :f64] [:gradient [:array 13 p/Vec3]] [:product [:array 13 p/Vec3]]])

(az/defn probe-assembly AssemblyProbe
  [[cells [:array 2 mixed/Element]] [values [:array 13 p/Vec3]]
   [predicted [:array 13 p/Vec3]] [loads [:array 13 p/Vec3]]
   [gravity p/Vec3] [duration :f64] [direction [:array 13 p/Vec3]]]
  (let [^:var elements cells
        ^:var coefficients values
        ^:var prediction predicted
        ^:var forces loads
        ^:var input direction
        ^:var tangents (mem/zeroes (az/type [:array 2 mixed/QuadraticResponse]))
        ^:var result (mem/zeroes (az/type AssemblyProbe))]
    (set! (az/field result response)
          (mixed/assemble-step! 5 (az/slice elements 0 2) (az/slice coefficients 0 13)
            (az/slice prediction 0 13) gravity (az/slice forces 0 13) duration (mixed/quadrature 6)
            (az/slice (az/field result gradient) 0 13) (az/slice tangents 0 2)))
    (when (az/field (az/field result response) valid)
      (dotimes [cell 2]
        (ak/+= (az/field result cached-energy) (az/field (az/index tangents cell) energy)))
      (mixed/tangent-product! 5 (az/slice elements 0 2) (az/slice tangents 0 2)
        (az/slice input 0 13) (az/slice (az/field result product) 0 13)))
    result))

(def assembly-elements
  [{:vertices [0 1 2 3] :gradients gradients :volume (/ 1.0 6.0) :density 120.0
    :material (az/value (elastic/material 10000.0 0.3))}
   {:vertices [1 2 3 4]
    :gradients (mapv vec3 [[0.5 -0.5 -0.5] [-0.5 0.5 -0.5] [-0.5 -0.5 0.5] [0.5 0.5 0.5]])
    :volume (/ 1.0 3.0) :density 60.0
    :material (az/value (elastic/material 17000.0 0.2))}])

(def assembly-zero (vec (repeat 13 (vec3 [0.0 0.0 0.0]))))

(defn flat-vectors [values] (vec (mapcat vector-data values)))

(defn assembly-probe [values predicted loads gravity duration direction]
  (az/value (probe-assembly assembly-elements values predicted loads (vec3 gravity) duration direction)))

(deftest global-mixed-incremental-potential-and-tangent
  (let [values (mapv #(vec3 (mapv (fn [axis] (* 0.0001 (Math/sin (+ % axis)))) (range 3))) (range 13))
        predicted (mapv #(vec3 (mapv (fn [axis] (* 0.0002 (Math/cos (+ % axis)))) (range 3))) (range 13))
        loads (mapv #(vec3 [(* 0.2 %) -0.3 0.1]) (range 13))
        direction (mapv #(vec3 (mapv (fn [axis] (Math/cos (+ (* 0.4 %) axis))) (range 3))) (range 13))
        probe #(assembly-probe % predicted loads [0.1 -9.81 0.2] 0.013 direction)
        result (probe values)
        h 1e-7]
    (is (get-in result [:response :valid]))
    (is (< (abs (- (get-in result [:response :energy])
                   (- (:cached-energy result)
                      (reduce + (map * (flat-vectors loads) (flat-vectors values)))))) 1e-12))
    ;; Differentiate the global potential, including shared vertices, both
    ;; material/density blocks, gravity, applied forces and private inertia.
    (doseq [column (range 39)]
      (let [path [(quot column 3) ([:x :y :z] (mod column 3))]
            plus (probe (update-in values path + h))
            minus (probe (update-in values path - h))
            derivative (/ (- (get-in plus [:response :energy]) (get-in minus [:response :energy])) (* 2 h))]
        (is (< (abs (- derivative ((flat-vectors (:gradient result)) column))) 2e-6))))
    (let [shift (fn [scale] (mapv (fn [value d] (merge-with + value (update-vals d #(* scale %)))) values direction))
          plus (flat-vectors (:gradient (probe (shift h))))
          minus (flat-vectors (:gradient (probe (shift (- h)))))]
      (is (< (maximum (map - (map #(/ (- %1 %2) (* 2 h)) plus minus)
                           (flat-vectors (:product result)))) 1e-5)))))

(deftest uniform-free-fall-is-an-exact-assembled-solution
  (let [duration 0.01
        acceleration [0.0 -9.81 0.0]
        moved (vec (repeat 13 (vec3 (mapv #(* duration duration %) acceleration))))
        result (assembly-probe moved assembly-zero assembly-zero acceleration duration assembly-zero)]
    (is (get-in result [:response :valid]))
    ;; All 40 kg belong to r. An exact backward-Euler translation must satisfy
    ;; every equation, including the shared massless boundary equations.
    (is (< (maximum (flat-vectors (:gradient result))) 1e-9))
    (is (< (abs (- (get-in result [:response :inertial-energy]) (* 0.5 40 duration duration 9.81 9.81))) 1e-12))
    (is (< (abs (+ (get-in result [:response :load-potential]) (* 40 duration duration 9.81 9.81))) 1e-12)))
  (let [changed (into (vec (repeat 5 (vec3 [Double/NaN Double/NaN Double/NaN]))) (subvec assembly-zero 5))
        result (assembly-probe assembly-zero changed assembly-zero [0.0 0.0 0.0] 0.01 assembly-zero)]
    (is (get-in result [:response :valid]))
    (is (< (maximum (flat-vectors (:gradient result))) 1e-10))))

(deftest assembly-rejects-invalid-input
  (doseq [duration [0.0 -1.0 Double/NaN Double/POSITIVE_INFINITY 1e-300 1e300]]
    (is (not (get-in (assembly-probe assembly-zero assembly-zero assembly-zero [0.0 0.0 0.0] duration assembly-zero)
                    [:response :valid]))))
  (doseq [cells [(assoc-in assembly-elements [0 :vertices] [0 0 2 3])
                (assoc-in assembly-elements [0 :vertices 0] 5)
                (assoc-in assembly-elements [1 :density] -1.0)]]
    (is (not (get-in (az/value (probe-assembly cells assembly-zero assembly-zero assembly-zero
                               (vec3 [0.0 0.0 0.0]) 0.01 assembly-zero)) [:response :valid])))))

(def quadratic-zero (vec (repeat 14 (vec3 [0.0 0.0 0.0]))))

(defn evaluate-quadratic [values hessian?]
  (az/value (mixed/evaluate-quadratic gradients (/ 1.0 6.0)
              (elastic/material 10000.0 0.3) values (mixed/quadrature 6) hessian?)))

(deftest quadratic-trace-partition-and-polynomial-reproduction
  (doseq [coordinates [[0.1 0.2 0.3 0.4] [0.0 0.2 0.3 0.5]
                       [0.25 0.25 0.25 0.25] [0.5 0.5 0.0 0.0]]]
    (let [{:keys [values gradients]} (az/value (mixed/quadratic-basis coordinates gradients))
          ;; x^2 has vertex-1 coefficient 1, edge coefficients 0. Its private
          ;; coefficients are its exact L2 projection, not its vertex values.
          polynomial (concat [0.0 1.0 0.0 0.0] (repeat 6 0.0)
                             [(/ -1.0 15.0) 0.6 (/ -1.0 15.0) (/ -1.0 15.0)])]
      (is (< (abs (- 1.0 (reduce + values))) 2e-14))
      (is (< (maximum (reduce #(mapv + %1 (vector-data %2)) [0.0 0.0 0.0] gradients)) 2e-13))
      (is (< (abs (- (* (coordinates 1) (coordinates 1)) (reduce + (map * values polynomial)))) 2e-14))
      (is (< (maximum (map - [(* 2.0 (coordinates 1)) 0.0 0.0]
                           (reduce #(mapv + %1 %2) [0.0 0.0 0.0]
                                   (map (fn [g c] (mapv #(* c %) (vector-data g))) gradients polynomial)))) 2e-13))
      (when (some zero? coordinates)
        (is (every? #(>= % 0.0) (subvec values 0 10)))
        (is (every? zero? (subvec values 10)))))))

(deftest quadratic-material-gradient-and-tangent
  (let [values (mapv (fn [node] (vec3 (mapv #(* 0.0001 (Math/sin (+ node %))) (range 3)))) (range 14))
        result (evaluate-quadratic values true)
        h 1e-7]
    (is (:valid result))
    (doseq [column (range 42)]
      (let [path [(quot column 3) ([:x :y :z] (mod column 3))]
            plus (evaluate-quadratic (update-in values path + h) false)
            minus (evaluate-quadratic (update-in values path - h) false)
            energy-derivative (/ (- (:energy plus) (:energy minus)) (* 2.0 h))
            force-derivative (mapv #(/ (- %1 %2) (* 2.0 h)) (:gradient plus) (:gradient minus))
            expected (mapv #(nth (:hessian result) (+ (* 42 %) column)) (range 42))]
        (is (< (abs (- energy-derivative (nth (:gradient result) column))) 2e-7))
        (is (< (maximum (map - force-derivative expected)) 2e-5))))))

(deftest quadratic-whole-element-and-path-certificates
  (let [bound #(az/value (mixed/quadratic-path-bound gradients %1 %2))
        rest (bound quadratic-zero quadratic-zero)
        uniform (vec (repeat 14 (vec3 [0.003 -0.004 0.002])))
        translated (bound quadratic-zero uniform)
        hidden (assoc-in quadratic-zero [11 :x] 0.06)]
    (is (<= (:lower rest) 1.0 (:upper rest)))
    (is (> (:lower translated) 0.999999999))
    ;; Interior enrichment vanishes at every boundary trace control. A solver
    ;; checking only faces would miss this loss of whole-element certification.
    (is (not (pos? (:lower (bound quadratic-zero hidden)))))
    (is (neg? (:lower (bound quadratic-zero (assoc-in quadratic-zero [4 :x] Double/NaN)))))
    (let [corners (mapv (fn [[x y z]] (vec3 [(* -2.0 x) (* -2.0 y) 0.0])) reference)
          edges (mapv (fn [[a b]] (merge-with #(* 0.5 (+ %1 %2)) (corners a) (corners b)))
                      [[0 1] [0 2] [0 3] [1 2] [1 3] [2 3]])
          rotated (vec (concat corners edges corners))]
      (is (:valid (evaluate-quadratic rotated false)))
      ;; Two sign changes preserve final determinant; the path still collapses.
      (is (not (pos? (:lower (bound quadratic-zero rotated))))))))
