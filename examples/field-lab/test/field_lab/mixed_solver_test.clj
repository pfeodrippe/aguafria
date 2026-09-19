(ns field-lab.mixed-solver-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [field-lab.physics :as p]
            [field-lab.mixed-tetra :as mixed]
            [field-lab.mixed-solver :as solver]
            [field-lab.mixed-tetra-test :as fixture]))

(az/defstruct Probe {:layout :extern}
  [[:report solver/Report] [:x [:array 13 p/Vec3]] [:gradient [:array 13 p/Vec3]]])

(az/defn probe-solve Probe
  [[cells [:array 2 mixed/Element]] [start [:array 13 p/Vec3]] [predicted [:array 13 p/Vec3]]
   [forces [:array 13 p/Vec3]] [lo [:array 13 p/Vec3]] [hi [:array 13 p/Vec3]]
   [gravity p/Vec3] [duration :f64] [tolerance :f64] [iterations :u32] [cg-iterations :u32]]
  (let [^:var elements cells
        ^:var initial start
        ^:var prediction predicted
        ^:var loads forces
        ^:var lower lo
        ^:var upper hi
        ^:var vectors (mem/zeroes (az/type [:array 10 [:array 13 p/Vec3]]))
        ^:var tangents (mem/zeroes (az/type [:array 2 [:array 2 mixed/QuadraticResponse]]))
        ^:var problem (solver/Problem
                       {:vertex-count 5 :elements (az/slice elements 0 2)
                        :initial (az/slice initial 0 13) :prediction (az/slice prediction 0 13)
                        :loads (az/slice loads 0 13) :lower (az/slice lower 0 13) :upper (az/slice upper 0 13)
                        :gravity gravity :duration duration :quadrature (mixed/quadrature 6)
                        :force-tolerance tolerance :iteration-limit iterations :cg-limit cg-iterations})
        ^:var workspace (solver/Workspace
                         {:x (az/slice (az/index vectors 0) 0 13)
                          :trial (az/slice (az/index vectors 1) 0 13)
                          :gradient (az/slice (az/index vectors 2) 0 13)
                          :trial-gradient (az/slice (az/index vectors 3) 0 13)
                          :direction (az/slice (az/index vectors 4) 0 13)
                          :residual (az/slice (az/index vectors 5) 0 13)
                          :search (az/slice (az/index vectors 6) 0 13)
                          :product (az/slice (az/index vectors 7) 0 13)
                          :diagonal (az/slice (az/index vectors 8) 0 13)
                          :free (az/slice (az/index vectors 9) 0 13)
                          :tangents (az/slice (az/index tangents 0) 0 2)
                          :trial-tangents (az/slice (az/index tangents 1) 0 2)})
        ^:var result (mem/zeroes (az/type Probe))]
    (az/set-many! (az/field result report) (solver/solve! (ak/& problem) (ak/& workspace))
                  (az/field result x) (az/index vectors 0)
                  (az/field result gradient) (az/index vectors 2))
    result))

(def zero-state fixture/assembly-zero)

(def lower-free (vec (repeat 13 (fixture/vec3 (repeat 3 Double/NEGATIVE_INFINITY)))))

(def upper-free (vec (repeat 13 (fixture/vec3 (repeat 3 Double/POSITIVE_INFINITY)))))

(def floor-lower
  (reduce (fn [bounds [index rest-y]] (assoc-in bounds [index :y] (- rest-y)))
          lower-free (map-indexed vector [0.0 0.0 1.0 0.0 1.0])))

(defn solve-case [options]
  (let [{:keys [initial prediction loads lower upper gravity duration tolerance iterations cg-iterations cells]
         :or {initial zero-state prediction zero-state loads zero-state lower lower-free upper upper-free
              gravity [0.0 -9.81 0.0] duration 0.01 tolerance 1e-7 iterations 100 cg-iterations 100
              cells fixture/assembly-elements}} options]
    (az/value (probe-solve cells initial prediction loads lower upper (fixture/vec3 gravity)
                          duration tolerance iterations cg-iterations))))

(deftest nonlinear-free-fall-step
  (doseq [duration [0.01 0.02]]
    (let [{:keys [report x]} (solve-case {:duration duration})
          expected (repeat 13 (fixture/vec3 [0.0 (* -9.81 duration duration) 0.0]))]
      (is (= 0 (:status report)) (pr-str report))
      (is (<= (:force-residual report) 1e-7))
      (is (pos? (:path-lower-bound report)))
      (is (< (fixture/maximum (map - (fixture/flat-vectors x) (fixture/flat-vectors expected))) 1e-10)))))

(deftest frictionless-ground-step-satisfies-kkt-and-momentum
  (let [{:keys [report x gradient]} (solve-case {:lower floor-lower})
        reaction (reduce + (map :y (subvec gradient 0 5)))
        momentum-y (* 5.0 (reduce + (map :y (subvec x 5))) (/ 1.0 0.01))]
    (is (= 0 (:status report)) (pr-str report))
    (is (pos? (:path-lower-bound report)))
    (is (pos? reaction))
    (doseq [index (range 5)]
      (is (>= (get-in x [index :y]) (get-in floor-lower [index :y])))
      (when (= (get-in x [index :y]) (get-in floor-lower [index :y]))
        (is (>= (get-in gradient [index :y]) -1e-7))))
    ;; Global BE balance: momentum gain equals gravity plus normal reaction
    ;; impulse. Each of the eight private P1 velocity coefficients has row mass 5kg.
    (is (< (abs (- momentum-y (* 0.01 (+ (* 40.0 -9.81) reaction)))) 1e-7))
    (is (< (fixture/maximum (mapcat (fn [v] [(:x v) (:z v)]) gradient)) 1e-7))))

(deftest upper-bound-contact-and-fixed-supports
  (let [upper (reduce (fn [bounds [index value]] (assoc-in bounds [index :y] value))
                      upper-free (map-indexed vector [1.0 1.0 0.0 1.0 0.0]))
        {:keys [report x gradient]} (solve-case {:upper upper :gravity [0.0 9.81 0.0]})]
    (is (= 0 (:status report)) (pr-str report))
    (is (neg? (reduce + (map :y (subvec gradient 0 5)))))
    (doseq [index (range 5)] (is (<= (get-in x [index :y]) (get-in upper [index :y])))))
  (let [lower (reduce #(assoc %1 %2 (fixture/vec3 [0.0 0.0 0.0])) lower-free (range 5))
        upper (reduce #(assoc %1 %2 (fixture/vec3 [0.0 0.0 0.0])) upper-free (range 5))
        {:keys [report x]} (solve-case {:lower lower :upper upper})]
    (is (= 0 (:status report)) (pr-str report))
    (is (= (subvec x 0 5) (subvec zero-state 0 5)))
    (is (neg? (reduce + (map :y (subvec x 5)))))))

(deftest rejected-solve-is-not-success
  (doseq [options [{:tolerance 0.0} {:iterations 0} {:cg-iterations 0}
                   {:lower (assoc-in lower-free [0 :y] 0.1)}
                   {:upper (assoc-in upper-free [0 :y] Double/NaN)}]]
    (is (= 1 (get-in (solve-case options) [:report :status]))))
  (is (= 4 (get-in (solve-case {:lower floor-lower :iterations 1}) [:report :status])))
  (let [hidden (assoc-in zero-state [6 :x] 0.058869298663277475)]
    (is (= 2 (get-in (solve-case {:initial hidden}) [:report :status])))))
