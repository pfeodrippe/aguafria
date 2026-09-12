(ns field-lab.fem-test
  (:require [clojure.test :refer [deftest is testing]]
            [field-lab.fem-job :as job]))

(def material {:young-Pa 2.0e6 :poisson-ratio 0.3})

(defn maximum-error [actual expected]
  (reduce max 0.0 (map #(abs (- %1 %2)) (flatten actual) (flatten expected))))

(defn boundary? [point]
  (some #(or (zero? %) (= 1.0 %)) point))

(defn affine [[x y z]]
  [(+ 0.001 (* 0.002 x) (* 0.003 y))
   (+ -0.002 (* -0.001 x) (* -0.0006 y))
   (+ 0.003 (* -0.0006 z))])

(deftest affine-patch-stress-energy-and-reactions
  (let [mesh (job/box-mesh [2 2 2] [1.0 1.0 1.0])
        description {:mesh mesh :material material
                     :constraints (job/prescribed mesh boundary? affine)}
        result (job/solve! description)
        expected (mapv affine (:points mesh))
        mu (/ 2.0e6 2.6)
        stress [4000.0 (* mu 0.002) 0.0 (* mu 0.002) 0.0 0.0 0.0 0.0 0.0]
        expected-energy (+ 4.0 (* mu 0.000002))]
    (is (get-in result [:report :converged]) (pr-str (:report result)))
    (is (< (maximum-error (:displacements-m result) expected) 1.0e-11))
    (is (< (maximum-error (:stress-Pa result) (repeat (count (:cells mesh)) stress)) 1.0e-5))
    (is (< (abs (- expected-energy (get-in result [:report :strain-energy]))) 1.0e-7))
    (is (< (reduce max (map abs (apply mapv + (:reactions-N result)))) 1.0e-6))
    (testing "Element winding does not change the material or equilibrium"
      (let [reversed (job/solve! (update-in description [:mesh :cells]
                                           #(mapv (fn [[a b c d]] [a c b d]) %)))]
        (is (< (maximum-error (:displacements-m reversed) (:displacements-m result)) 1.0e-12))))))

(deftest rigid-motion-has-no-strain
  (let [mesh (job/box-mesh [2 2 2] [1.0 1.0 1.0])
        rigid (fn [[x y z]] [(+ 0.01 (* -0.002 y)) (+ 0.02 (* 0.002 x)) (+ 0.03 (* 0.0 z))])
        result (job/solve! {:mesh mesh :material material
                            :constraints (job/prescribed mesh boundary? rigid)})]
    (is (get-in result [:report :converged]))
    (is (< (maximum-error (:displacements-m result) (mapv rigid (:points mesh))) 1.0e-11))
    (is (< (reduce max (map abs (flatten (:stress-Pa result)))) 1.0e-6))
    (is (< (abs (get-in result [:report :strain-energy])) 1.0e-8))))

(defn manufactured-result [refinement]
  (let [mesh (job/box-mesh [refinement refinement refinement] [1.0 1.0 1.0])
        amplitude 0.001
        mu (/ (:young-Pa material) (* 2.0 (+ 1.0 (:poisson-ratio material))))
        lambda (/ (* (:young-Pa material) (:poisson-ratio material))
                  (* (+ 1.0 (:poisson-ratio material)) (- 1.0 (* 2.0 (:poisson-ratio material)))))
        exact (fn [[x _ _]] [(* amplitude x x) 0.0 0.0])
        result (job/solve! {:mesh mesh :material material
                            :constraints (job/prescribed mesh boundary? exact)
                            :loads (job/body-load mesh [(* -2.0 amplitude (+ lambda (* 2.0 mu))) 0.0 0.0])})
        high (/ (+ 5.0 (* 3.0 (Math/sqrt 5.0))) 20.0)
        low (/ (- 5.0 (Math/sqrt 5.0)) 20.0)
        quadrature (mapv #(assoc [low low low low] % high) (range 4))
        error-squared
        (reduce +
                (for [cell (:cells mesh)
                      :let [{:keys [volume gradients]} (job/tetrahedron (:points mesh) cell)
                            displacement (mapv (:displacements-m result) cell)
                            gradient (mapv (fn [row]
                                             (reduce #(mapv + %1 %2) [0.0 0.0 0.0]
                                                     (map #(job/scale (%1 row) %2) displacement gradients)))
                                           (range 3))]
                      weights quadrature
                      :let [x (reduce + (map #(* %1 (first ((:points mesh) %2))) weights cell))
                            exact-gradient [[(* 2.0 amplitude x) 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]]]]
                  (* (/ volume 4.0)
                     (reduce + (map #(let [error (- %1 %2)] (* error error))
                                    (flatten gradient) (flatten exact-gradient))))))]
    {:refinement refinement :report (:report result) :gradient-error (Math/sqrt error-squared)}))

(deftest manufactured-solution-spatial-convergence
  (let [results (mapv manufactured-result [2 4 8])
        errors (mapv :gradient-error results)]
    (is (every? #(get-in % [:report :converged]) results) (pr-str results))
    ;; P1 tetrahedra have first-order H1 convergence for this smooth solution.
    (is (every? #(< 1.8 % 2.2) (map / errors (rest errors))) (pr-str results))))

(deftest cantilever-load-balance-and-refinement
  (let [results (mapv #(job/solve! (job/cantilever %)) [1 2 4 8])
        tip (fn [result]
              (let [indices (keep-indexed #(when (= 1.0 (first %2)) %1) (get-in result [:job :mesh :points]))]
                (/ (reduce + (map #(get-in result [:displacements-m % 1]) indices)) (count indices))))
        deflections (mapv #(abs (tip %)) results)
        beam-reference (/ 10.0 (* 3.0 2.0e9 (/ (* 0.1 (Math/pow 0.1 3.0)) 12.0)))]
    (is (every? #(get-in % [:report :converged]) results) (pr-str (mapv :report results)))
    (is (apply < deflections) (pr-str deflections))
    (is (< (last deflections) (* 1.1 beam-reference)) (pr-str deflections))
    (is (< (abs (- (last deflections) beam-reference)) (* 0.1 beam-reference)) (pr-str deflections))
    (doseq [result results]
      (let [clamped (keep-indexed #(when (zero? (first %2)) %1) (get-in result [:job :mesh :points]))
            reaction (reduce + (map #(get-in result [:reactions-N % 1]) clamped))]
        (is (< (abs (- reaction 10.0)) 1.0e-5) (pr-str (:report result)))))))

(deftest invalid-and-unconverged-jobs-are-explicit
  (let [description (job/cantilever 1)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rigid"
                          (job/solve! (assoc description :constraints []))))
    (is (thrown? clojure.lang.ExceptionInfo
                  (job/solve! (assoc-in description [:material :poisson-ratio] 0.5))))
    (is (thrown? clojure.lang.ExceptionInfo
                  (job/solve! (update description :constraints conj [0 0 0.1]))))
    (let [result (job/solve! (assoc description :max-iterations 1))]
      (is (false? (get-in result [:report :converged])))
      (is (= 1 (get-in result [:report :iterations]))))))
