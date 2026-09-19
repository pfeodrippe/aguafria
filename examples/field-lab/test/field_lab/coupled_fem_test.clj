(ns field-lab.coupled-fem-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.contact-mesh :as contact]
            [field-lab.coupled-fem :as coupled]
            [field-lab.coupled-job :as joint]
            [pitoco.geometry :as geometry]))

(az/defstruct ImpulseCheck {:layout :extern}
  [[:normal-impulse :f64] [:tangent-impulse :f64]
   [:normal-speed :f64] [:tangent-speed :f64]
   [:momentum-error :f64] [:kinetic-before :f64] [:kinetic-after :f64]])

(az/defn impulse-probe! ImpulseCheck
  "Isolate one vertex/face impulse with unequal FEM lumped node masses."
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

(deftest compiled-kernel-result-and-cancellation
  (let [options {:bodies 2 :refinement 0 :seconds 0.012 :maximum-step 0.00005
                 :geometry :sphere :contact-method :discrete}
        baseline (joint/head-on-study! options)]
    (joint/with-compiled-kernel!
      (fn []
        (let [snapshot (joint/head-on-study! options)]
          (is (= (:history baseline) (:history snapshot)))
          (is (= (:reports baseline) (:reports snapshot)))
          (is (= :standalone-snapshot (get-in snapshot [:solver-version 'field-lab.coupled-job/kernel :mode]))))
        (joint/with-system! [(joint/sphere {:refinement 0 :velocity [1.0 0.0 0.0]})]
          (fn [assembly [state] _]
            (let [cancel (atom false)
                  failure (try
                            (joint/advance! assembly 0.001 0.00005
                                            {:maximum-attempts 1 :cancelled? #(deref cancel)
                                             :on-progress (fn [_] (reset! cancel true))})
                            nil
                            (catch clojure.lang.ExceptionInfo error (ex-data error)))]
              (is (:cancelled? failure))
              (is (= 1 (get-in failure [:report :attempts])))
              (is (= 0.00005 (get-in failure [:report :time])))
              (joint/advance! assembly 0.001 0.00005)
              (is (< (abs (- 0.00105 (get-in (az/value (dynamics/evaluate! state)) [:center :x]))) 1.0e-12)))))))))

(deftest compiled-kernel-rejects-stale-and-closed-handles
  (joint/with-system! [(joint/sphere {:refinement 0 :velocity [1.0 0.0 0.0]})]
    (fn [assembly [state] _]
      (let [before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s])
            escaped (joint/with-compiled-kernel!
                      (fn []
                        (with-redefs [coupled/advance-explicit-batch! (fn [& _] (throw (ex-info "Unexpected fallback" {})))]
                          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Solver changed"
                                               (joint/advance! assembly 0.001 0.00005))))
                        joint/*explicit-batch*))
            task (coupled/create-explicit-task! assembly 0.001 0.00005)]
        (try
          (is (thrown? IllegalStateException (escaped task 1)))
          (is (= before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s])))
          (finally (coupled/destroy-explicit-task! task)))))))

(deftest velocity-observables-match-full-assembly
  (joint/with-system!
    [(joint/sphere {:refinement 0 :center [-0.2 1.0 0.0] :density 900.0})
     (joint/sphere {:refinement 1 :center [0.2 1.0 0.0] :density 1300.0})]
    (fn [assembly states descriptions]
      (let [previous (coupled/observe! assembly)]
        (doseq [[state description] (map vector states descriptions)
                node (range (count (get-in description [:mesh :points])))]
          (dynamics/set-particle! state node (dynamics/position state node)
                                 {:x (* node 0.01) :y (- 0.1 (* node 0.02)) :z 0.03}))
        (let [refreshed (az/value (coupled/refresh-motion! assembly previous))]
          (is (= refreshed (az/value (coupled/observe! assembly))))
          (is (pos? (:kinetic-energy refreshed)))))
      ;; Exercise the guard: a position edit invalidates strain energy, bounds,
      ;; center, and potential energy even though the previous observation exists.
      (let [previous (coupled/observe! assembly)
            state (first states)
            point (az/value (dynamics/position state 1))]
        (dynamics/set-particle! state 1 (update point :x + 0.01)
                               (dynamics/particle-velocity state 1))
        (let [refreshed (az/value (coupled/refresh-motion! assembly previous))]
          (is (= refreshed (az/value (coupled/observe! assembly))))
          (is (> (:elastic-energy refreshed) (:elastic-energy (az/value previous)))))))))

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

(deftest coupled-normal-complementarity
  (doseq [[matrix velocity expected]
          [[[[2.0 -1.0] [-1.0 2.0]] [-1.0 -1.0] [1.0 1.0]]
           [[[1.0 0.8] [0.8 1.0]] [-0.1 -1.0] [0.0 1.0]]
           [[[1.0 -0.8] [-0.8 1.0]] [0.1 -1.0] [(/ 0.7 0.36) (/ 0.92 0.36)]]
           [[[4.0 0.0] [0.0 0.25]] [-2.0 3.0] [0.5 0.0]]]]
    (let [{:keys [report impulses]} (joint/normal-impulses! matrix velocity)
          final (mapv #(+ %1 (reduce + (map * %2 impulses))) velocity matrix)]
      (is (zero? (:status report)))
      (is (every? #(< (abs %) 1.0e-10) (map - expected impulses)))
      (is (every? #(>= % -1.0e-10) final))
      (is (every? #(< (abs %) 1.0e-10) (map * final impulses)))))
  (doseq [[matrix velocity]
          [[[[1.0 1.0] [1.0 1.0]] [-1.0 -1.0]]
           [[[1.0 -1.0] [-1.0 1.0]] [-1.0 1.0]]]]
    (let [{:keys [impulses]} (joint/normal-impulses! matrix velocity)]
      (is (every? #(>= % 0.0) impulses))
      (is (< (abs (- 1.0 (first impulses))) 1.0e-12))
      (is (zero? (second impulses))))))

(deftest nearly-opposed-contact-rows
  ;; Two unit-mass velocity DOFs, initially [-1,-1]. The cone's closest point
  ;; is [0,0]. Local sweeps converge very slowly as the normals become opposed.
  (doseq [angle [0.01 0.003 0.001]]
    (let [c (Math/cos angle)
          s (Math/sin angle)
          matrix [[1.0 (- c)] [(- c) 1.0]]
          {:keys [report impulses]} (joint/normal-impulses! matrix [-1.0 (- c s)])
          [a b] impulses
          velocity [(+ -1.0 a (* (- c) b)) (+ -1.0 (* s b))]]
      (is (<= (:pivots report) 4))
      (is (every? pos? impulses))
      (is (every? #(< (abs %) 1.0e-8) velocity))
      (is (< (* 0.5 (reduce + (map #(* % %) velocity))) 1.0e-15)))))

(deftest normal-solve-input-contract
  (doseq [[matrix velocity] [[[[1.0 0.0] [0.1 1.0]] [-1.0 -1.0]]
                             [[[0.0]] [-1.0]]
                             [[[Double/NaN]] [-1.0]]
                             [[[1.0]] [Double/POSITIVE_INFINITY]]
                             [[] []]]]
    (is (thrown? clojure.lang.ExceptionInfo (joint/normal-impulses! matrix velocity)))))

(defn edge-impact-bodies [gap friction]
  [{:mesh {:points [[-1.0 0.0 0.0] [1.0 0.0 0.0] [-1.0 -1.0 -0.5] [-1.0 0.0 -0.5]]
           :cells [[0 1 2 3]]}
    :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 1200.0
    :gravity [0.0 0.0 0.0] :floor? false :friction friction
    :initial-velocities (vec (repeat 4 [0.0 0.0 0.0]))}
   {:mesh {:points [[0.0 -1.0 gap] [0.0 1.0 gap] [1.0 -1.0 (+ gap 0.5)] [0.0 -1.0 (+ gap 0.5)]]
           :cells [[0 2 1 3]]}
    :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 800.0
    :gravity [0.0 0.0 0.0] :floor? false :friction friction
    :initial-velocities (vec (repeat 4 [1.0 0.0 -2.0]))}])

(defn edge-feature [descriptions]
  (let [selection (fn [description]
                    (first (for [[face nodes] (map-indexed vector (geometry/boundary-faces (:mesh description)))
                                 edge (range 3)
                                 :when (= #{0 1} #{(nodes edge) (nodes (mod (inc edge) 3))})]
                             [face edge])))
        [[face-a edge-a] [face-b edge-b]] (mapv selection descriptions)]
    [face-a face-b (+ 6 (* 3 edge-a) edge-b)]))

(defn system-snapshot [states]
  (mapv #(job/snapshot % 4) states))

(defn angular-momentum [snapshots]
  (reduce (partial mapv +) [0.0 0.0 0.0]
          (for [{:keys [positions-m velocities-m-s observables]} snapshots
                [point velocity] (map vector positions-m velocities-m-s)]
            (geometry/scale (/ (:mass observables) 4.0) (geometry/cross point velocity)))))

(deftest edge-contact-impulses
  (doseq [friction [0.0 0.3 3.0]]
    (let [descriptions (edge-impact-bodies 1.0e-6 friction)]
      (joint/with-system! descriptions
        (fn [assembly states _]
          (let [constraint (az/value (apply coupled/feature-contact assembly 0 1 (edge-feature descriptions)))
                row (:row constraint)
                before (system-snapshot states)
                response (az/value (coupled/resolve-velocity-contact! assembly row))
                after (system-snapshot states)
                relative (job/vector-data (coupled/contact-row-velocity assembly row))
                normal ((juxt :x :y :z) (:normal row))
                tangent (geometry/subtract relative (geometry/scale (geometry/dot relative normal) normal))
                before-momentum (mapv #(get-in % [:observables :momentum]) before)
                after-momentum (mapv #(get-in % [:observables :momentum]) after)
                momentum-error (reduce (partial merge-with +) (map #(merge-with - %1 %2) after-momentum before-momentum))
                angular-error (geometry/length (geometry/subtract (angular-momentum after) (angular-momentum before)))]
            (is (:valid constraint))
            (is (< (abs (- 1.0e-6 (:distance constraint))) 1.0e-14))
            (is (pos? (:normal-impulse response)))
            (is (<= (:tangent-impulse response) (* friction (:normal-impulse response))))
            (is (< (abs (geometry/dot relative normal)) 1.0e-12))
            (is (< (abs (- (max 0.0 (- 1.0 (* friction 2.0))) (geometry/length tangent))) 1.0e-12))
            (is (< (norm momentum-error) 1.0e-11))
            ;; Tangential impulses across a finite gap create a bounded numerical
            ;; couple. Normal-only response conserves angular momentum exactly.
            (is (<= angular-error (+ 1.0e-11 (* (:distance constraint) (:tangent-impulse response)))))
            (is (<= (reduce + (map #(get-in % [:observables :kinetic-energy]) after))
                    (reduce + (map #(get-in % [:observables :kinetic-energy]) before))))))))))

(deftest continuous-edge-drift-and-rollback
  (let [descriptions (edge-impact-bodies 1.0e-6 0.0)]
    (joint/with-system! descriptions
      (fn [assembly states _]
        (let [workspace (coupled/create-drift-workspace! assembly)
              before (system-snapshot states)]
          (try
            (doseq [[passes work status] [[1 100000 3] [128 1 4]]]
              (let [result (az/value (coupled/continuous-drift! assembly workspace 0.0001 1.0e-5 passes work 1000000))]
                (is (false? (:completed result)))
                (is (= status (:status result)))
                (is (= before (system-snapshot states)))
                (is (zero? (:pair-impulse result)))))
            (let [result (az/value (coupled/continuous-drift! assembly workspace 0.0001 1.0e-5 128 100000 1000000))
                  after (system-snapshot states)
                  motions (mapv (fn [index]
                                  {:start (get-in before [index :positions-m])
                                   :end (get-in after [index :positions-m])
                                   :faces (geometry/boundary-faces (:mesh (descriptions index)))}) (range 2))]
              (is (:completed result) (pr-str result))
              (is (pos? (:pair-impulse result)))
              (is (> (:passes result) 1))
              (is (false? (:possible-contact? (apply contact/sweep-meshes! motions))))
              (is (pos? (- (apply min (map #(nth % 2) (get-in after [1 :positions-m])))
                           (apply max (map #(nth % 2) (get-in after [0 :positions-m])))))))
            (finally (coupled/destroy-drift-workspace! workspace)))))))
  (joint/with-system! (edge-impact-bodies 0.01 0.0)
    (fn [assembly states _]
      (let [workspace (coupled/create-drift-workspace! assembly)
            before (system-snapshot states)]
        (try
          (let [result (az/value (coupled/continuous-drift! assembly workspace 0.01 1.0e-5 128 100000 1000000))]
            (is (= 1 (:status result)))
            (is (<= 0.1 (:safe-fraction result) 0.5))
            (is (= before (system-snapshot states))))
          (finally (coupled/destroy-drift-workspace! workspace)))))))

(deftest continuous-fem-edge-impact
  (let [descriptions (edge-impact-bodies 0.0001 0.0)]
    (joint/with-system! descriptions
      (fn [assembly states _]
        (let [before (az/value (coupled/observe! assembly))
              result (joint/advance-continuous! assembly 0.0002 0.00005)
              after (az/value (coupled/observe! assembly))]
          (is (:completed result))
          (is (= 0.0002 (:time result)))
          (is (pos? (:pair-impulse result)))
          (is (pos? (:ccd-queries result)))
          (is (> (:minimum-jacobian result) 0.99))
          (is (<= (:maximum-penetration result) 1.0e-9))
          (is (< (norm (merge-with - (:momentum after) (:momentum before))) 1.0e-9))
          (is (<= (energy after) (+ 1.0e-7 (energy before)))))))))

(deftest continuous-plane-impulse-balance
  (let [description {:mesh {:points [[0.0 0.0001 0.0] [0.02 0.0001 0.0]
                                    [0.0 0.02 0.0] [0.0 0.0001 0.02]]
                             :cells [[0 1 2 3]]}
                     :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 1000.0
                     :gravity [0.0 -9.81 0.0] :floor? true :friction 0.4
                     :initial-velocities (vec (repeat 4 [0.4 -1.0 0.0]))}]
    (doseq [step [1.0e-5 5.0e-6]]
      (joint/with-system! [description]
        (fn [assembly states _]
          (let [before (az/value (coupled/observe! assembly))
                result (joint/advance-continuous! assembly 0.0003 step)
                after (az/value (coupled/observe! assembly))
                residual (- (get-in after [:momentum :y]) (get-in before [:momentum :y])
                            (* (:mass before) -9.81 0.0003) (:ground-impulse result))]
            (is (:completed result))
            (is (pos? (:ground-impulse result)))
            (is (>= (:minimum-height after) 0.0))
            (is (< (abs residual) 1.0e-12))
            (is (<= (energy after) (+ (energy before) 1.0e-10)))
            (is (zero? (:ccd-queries result)))))))))

(deftest continuous-free-flight-and-input-contract
  (let [description (joint/sphere {:refinement 0 :center [0.0 1.0 0.0]
                                    :velocity [0.1 0.2 -0.3] :gravity [0.0 -9.81 0.0]})]
    (joint/with-system! [description]
      (fn [assembly states _]
        (let [state (first states)
              result (joint/advance-continuous! assembly 0.01 0.00005)]
          (is (:completed result))
          (is (zero? (:pair-impulse result)))
          (doseq [[node point] (map-indexed vector (get-in description [:mesh :points]))]
            (let [expected (mapv #(+ %1 (* 0.01 %2) (* 0.5 0.01 0.01 %3))
                                 point [0.1 0.2 -0.3] [0.0 -9.81 0.0])
                  actual (job/vector-data (dynamics/position state node))]
              (is (every? #(< (abs %) 1.0e-11) (map - actual expected)))))
          (doseq [clearance [0.0 1.0e-13 0.1 Double/NaN]]
            (is (thrown? clojure.lang.ExceptionInfo
                         (joint/advance-continuous! assembly 0.001 0.00005 clearance)))))))))

(az/defn velocity-only-displacement-probe! :f64
  [[assembly [:* coupled/Assembly]]]
  (let [body (az/index (az/field assembly bodies) 0)
        mesh (az/field (az/field body state) mesh)]
    ;; This displacement is below one ulp of the reference x=-1 coordinate.
    ;; Reconstructing it from position would erase it even with a zero move.
    (set! (az/index (az/field mesh displacement) 0) 1.0e-20)
    (coupled/apply-correction! body 0 (p/v 0.0 0.0 0.0) (p/v 0.1 0.0 0.0) false)
    (az/index (az/field mesh displacement) 0)))

(deftest velocity-only-impulses-preserve-displacement-bits
  (joint/with-system! (edge-impact-bodies 0.01 0.0)
    (fn [assembly _ _]
      (is (= 1.0e-20 (velocity-only-displacement-probe! assembly))))))

(deftest continuous-resting-gap-below-query-tolerance
  ;; Last accepted state of the two-body 12 ms regression, previously stalled
  ;; at 3.286796 ms. Keep numerical input, not generated solver provenance.
  (let [descriptions (edn/read-string (slurp (io/resource "field_lab/resting_contact.edn")))]
    (joint/with-system! descriptions
      (fn [assembly states _]
        (let [workspace (coupled/create-drift-workspace! assembly)
              snapshot #(mapv (fn [state description]
                                (job/snapshot state (count (get-in description [:mesh :points]))))
                              states descriptions)
              before (snapshot)]
          (try
            (let [result (az/value (coupled/continuous-drift! assembly workspace 0.00001 1.0e-5 128 100000 1000000))
                  after (snapshot)
                  motions (mapv (fn [index]
                                  {:start (get-in before [index :positions-m])
                                   :end (get-in after [index :positions-m])
                                   :faces (geometry/boundary-faces (:mesh (descriptions index)))}) (range 2))]
              (is (:completed result) (pr-str result))
              (is (pos? (:queries result)))
              (is (zero? (:pair-impulse result)))
              (is (= (mapv :velocities-m-s before) (mapv :velocities-m-s after)))
              (is (false? (:possible-contact? (apply contact/sweep-meshes! motions))))
              (is (false? (:possible-contact? (contact/sweep-meshes! (motions 0) (motions 1)
                                                                 {:tolerance 1.0e-10})))))
            (finally (coupled/destroy-drift-workspace! workspace))))))))


(deftest continuous-attempt-budget-and-resume
  (let [description (joint/sphere {:refinement 0 :center [0.0 0.2 0.0] :gravity [0.0 -9.81 0.0]})
        duration 0.001
        maximum-step 0.00005
        reference (joint/with-system! [description]
                    (fn [assembly states _]
                      (joint/advance-continuous! assembly duration maximum-step)
                      (job/snapshot (first states) (count (get-in description [:mesh :points])))))]
    (joint/with-system! [description]
      (fn [assembly states _]
        (let [snapshot #(job/snapshot (first states) (count (get-in description [:mesh :points])))
              initial (snapshot)
              stopped (joint/advance-continuous-bounded! assembly duration maximum-step 1.0e-5 0)]
          (is (:budget-exhausted stopped))
          (is (zero? (:attempts stopped)))
          (is (= initial (snapshot)))
          (loop [time 0.0 batches 0]
            (let [report (joint/advance-continuous-bounded! assembly (- duration time) maximum-step 1.0e-5 3)]
              (is (<= (:attempts report) 3))
              (is (<= (:time report) duration))
              (if (:completed report)
                (do
                  (is (> batches 1))
                  (is (= (:positions-m reference) (:positions-m (snapshot))))
                  (is (= (:velocities-m-s reference) (:velocities-m-s (snapshot)))))
                (do
                  (is (:budget-exhausted report))
                  (is (> (:time report) time))
                  (recur (:time report) (inc batches))))))
          (doseq [budget [-1 1.5 4294967296]]
            (is (thrown? clojure.lang.ExceptionInfo
                         (joint/advance-continuous-bounded! assembly duration maximum-step 1.0e-5 budget)))))))))

(deftest continuous-budget-resumes-rejected-step
  (let [descriptions (mapv (fn [[center velocity]]
                            (joint/sphere {:center center :velocity velocity :refinement 0 :geometry :polyhedron}))
                          [[[-0.0525 0.0 0.0] [1.0 0.0 0.0]] [[0.0525 0.0 0.0] [-1.0 0.0 0.0]]])]
    (joint/with-system! descriptions
      (fn [assembly _ _]
        (loop [time 0.0 step 0.00005 batches 0 rejected 0]
          (let [report (joint/advance-continuous-bounded! assembly (- 0.0026 time) step 1.0e-5 1)]
            (is (<= (:attempts report) 1))
            (if (:completed report)
              (do (is (pos? rejected)) (is (< (abs (- 0.0026 (:time report))) 1.0e-15)))
              (do
                (is (< batches 100))
                (when (< batches 100)
                  (recur (:time report)
                         (if (pos? (:substeps report)) 0.00005 (:next-step report))
                         (inc batches) (+ rejected (:rejected report))))))))))))

(deftest explicit-attempt-budget-preserves-trajectory
  (let [description (joint/sphere {:refinement 0 :center [0.0 0.2 0.0]
                                    :gravity [0.0 -9.81 0.0]})
        node-count (count (get-in description [:mesh :points]))
        duration 0.001
        reference (joint/with-system! [description]
                    (fn [assembly states _]
                      (coupled/advance! assembly duration 0.00005)
                      (job/snapshot (first states) node-count)))]
    (doseq [budget [1 3 8 64]]
      (joint/with-system! [description]
        (fn [assembly states _]
          (let [task (coupled/create-explicit-task! assembly duration 0.00005)
                snapshot #(job/snapshot (first states) node-count)
                initial (snapshot)]
            (try
              (is (false? (:completed (az/value (coupled/advance-explicit-batch! task 0)))))
              (is (zero? (:attempts (az/value (coupled/explicit-progress task)))))
              (is (= initial (snapshot)))
              (loop [previous-attempts 0 batches 0]
                (let [report (az/value (coupled/advance-explicit-batch! task budget))
                      progress (az/value (coupled/explicit-progress task))]
                  (is (<= (- (:attempts progress) previous-attempts) budget))
                  (is (<= (:time report) duration))
                  (if (:completed report)
                    (do
                      (is (= 1 (:status progress)))
                      (is (= (:positions-m reference) (:positions-m (snapshot)))))
                    (do
                      (is (zero? (:status progress)))
                      (is (< batches 30))
                      (when (< batches 30)
                        (recur (:attempts progress) (inc batches)))))))
              (is (= (:velocities-m-s reference) (:velocities-m-s (snapshot))))
              (finally (coupled/destroy-explicit-task! task)))))))))

(deftest explicit-host-cancellation-keeps-accepted-state
  (let [description (joint/sphere {:refinement 0 :center [0.0 0.2 0.0]
                                    :gravity [0.0 -9.81 0.0]})
        node-count (count (get-in description [:mesh :points]))]
    (joint/with-system! [description]
      (fn [assembly states _]
        (let [initial (job/snapshot (first states) node-count)
              reports (atom [])
              outcome (try
                        (joint/advance! assembly 0.1 0.00005
                                        {:maximum-attempts 3
                                         :cancelled? #(>= (count @reports) 2)
                                         :on-progress #(swap! reports conj %)})
                        (catch clojure.lang.ExceptionInfo error (ex-data error)))
              accepted (last @reports)]
          (is (:cancelled? outcome))
          (is (= 2 (count @reports)))
          (is (= 6 (:attempts accepted)))
          (is (< 0.0 (:time accepted) 0.1))
          (is (not= initial (job/snapshot (first states) node-count)))
          (is (:completed (joint/advance! assembly (- 0.001 (:time accepted)) 0.00005)))
          (let [result (job/snapshot (first states) node-count)]
            (joint/with-system! [description]
              (fn [reference reference-states _]
                (joint/advance! reference 0.001 0.00005)
                (let [expected (job/snapshot (first reference-states) node-count)]
                  (is (= (:positions-m expected) (:positions-m result)))
                  (is (= (:velocities-m-s expected) (:velocities-m-s result))))))))))))

(deftest explicit-contact-state-survives-batch-boundaries
  ;; The authored box/tetrahedron previously stalled at its first contact.
  ;; It must now finish this frame identically across native batch budgets.
  (let [bodies (:bodies (load-file "scenes/solid-impact.clj"))
        node-counts (mapv #(count (get-in % [:mesh :points])) bodies)
        contact-start
        (joint/with-system! bodies
          (fn [assembly states _]
            (dotimes [_ 60] (joint/advance! assembly (/ 1.0 240.0) 0.00005))
            (mapv (fn [body state nodes]
                    (let [snapshot (job/snapshot state nodes)]
                      (assoc body :initial-positions (:positions-m snapshot)
                                  :initial-velocities (:velocities-m-s snapshot))))
                  bodies states node-counts)))
        run-batches
        (fn [budget]
          (joint/with-system! contact-start
            (fn [assembly states _]
              (let [task (coupled/create-explicit-task! assembly (/ 1.0 240.0) 0.00005)]
                (try
                  (dotimes [_ (quot 256 budget)]
                    (let [before (az/value (coupled/explicit-progress task))
                          report (az/value (coupled/advance-explicit-batch! task budget))
                          after (az/value (coupled/explicit-progress task))]
                      (is (<= 0 (- (:attempts after) (:attempts before)) budget))
                      (is (= (:completed report) (= 1 (:status after))))
                      (is (#{0 1} (:status after)))))
                  (let [report (az/value (coupled/advance-explicit-batch! task 0))]
                    (is (:completed report))
                    {:report report
                     :progress (az/value (coupled/explicit-progress task))
                     :states (mapv #(select-keys (job/snapshot %1 %2)
                                                [:positions-m :velocities-m-s]) states node-counts)})
                  (finally (coupled/destroy-explicit-task! task)))))))
        reference (run-batches 64)]
    (doseq [budget [1 8]]
      (is (= reference (run-batches budget))))))

(az/defstruct PositionCheck {:layout :extern}
  [[:report coupled/PositionReport] [:penetration-before :f64] [:penetration-after :f64]
   [:center-error :f64] [:momentum-error :f64] [:kinetic-error :f64] [:surface-error :f64]])

(az/defn position-block-probe! PositionCheck [[assembly [:* coupled/Assembly]]]
  ;; Start with disjoint solids, then construct an overlapping trial state.
  (dotimes [index 2]
    (let [body (az/index (az/field assembly bodies) index)
          state (az/field body state)]
      (dynamics/set-particle! state 1 (p/add (dynamics/position state 1) (p/v 0.02 0.0 0.0))
                               (dynamics/particle-velocity state 1))
      (coupled/synchronize! body)))
  (let [before (coupled/observe! assembly)
        penetration (az/field (coupled/residual assembly) penetration)
        report (coupled/resolve-position-block! assembly)
        after (coupled/observe! assembly)
        ^{:var :f64} surface-error 0.0]
    (dotimes [index (az/field (az/field assembly bodies) len)]
      (let [body (az/index (az/field assembly bodies) index)
            state (az/field body state)]
        (dotimes [node (az/field (az/field state masses) len)]
          (set! surface-error
                (ak/max surface-error
                        (p/length (p/add (dynamics/position state node)
                                         (p/scale (az/index (az/field (az/field body surface) points) node) -1.0))))))))
    (PositionCheck
     {:report report :penetration-before penetration
      :penetration-after (az/field (coupled/residual assembly) penetration)
      :center-error (p/length (p/add (az/field after center) (p/scale (az/field before center) -1.0)))
      :momentum-error (p/length (p/add (az/field after momentum) (p/scale (az/field before momentum) -1.0)))
      :kinetic-error (ak/abs (- (az/field after kinetic-energy) (az/field before kinetic-energy)))
      :surface-error surface-error})))

(deftest coupled-position-repair-preserves-mass-and-velocity
  (let [body (fn [points density velocity]
               {:mesh {:points points :cells [[0 1 2 3]]}
                :material {:young-Pa 10000.0 :poisson-ratio 0.3}
                :density-kg-m3 density :gravity [0.0 0.0 0.0] :floor? false :friction 0.0
                :initial-velocities (vec (repeat 4 velocity))})
        source (fn [height density]
                 (body [[-0.04 height 0.2] [-0.01 height 0.2]
                        [-0.04 (+ height 0.1) 0.2] [-0.04 height 0.3]]
                       density [1.0 0.2 0.3]))]
    (doseq [ratio [0.1 1.0 10.0]]
      (joint/with-system!
        [(source 0.2 1000.0) (source 0.5 (* ratio 1000.0))
         (body [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
               0.5 [-0.5 0.1 0.0])]
        (fn [assembly _ _]
          (let [result (az/value (position-block-probe! assembly))]
            (is (> (:penetration-before result) 0.005))
            (is (get-in result [:report :completed]) (pr-str result))
            (is (<= (:penetration-after result) 1.0e-9))
            (is (< (:center-error result) 1.0e-14))
            (is (zero? (:momentum-error result)))
            (is (zero? (:kinetic-error result)))
            (is (zero? (:surface-error result)))))))))

(deftest explicit-rejected-material-trial-resumes-with-reduced-step
  (let [description {:mesh {:points [[0.0 0.0 0.0] [0.1 0.0 0.0]
                                      [0.0 0.1 0.0] [0.0 0.0 0.1]]
                            :cells [[0 1 2 3]]}
                     :material {:young-Pa 1000.0 :poisson-ratio 0.3}
                     :density-kg-m3 1000.0 :gravity [0.0 0.0 0.0] :floor? false :friction 0.0
                     :initial-velocities [[0.0 0.0 0.0] [-100.0 0.0 0.0]
                                          [0.0 0.0 0.0] [0.0 0.0 0.0]]}
        run (fn [split?]
              (joint/with-system! [description]
                (fn [assembly states _]
                  (let [task (coupled/create-explicit-task! assembly 0.001 0.001)
                        snapshot #(select-keys (job/snapshot (first states) 4)
                                               [:positions-m :velocities-m-s])
                        initial (snapshot)]
                    (try
                      (when split?
                        (let [report (az/value (coupled/advance-explicit-batch! task 1))
                              progress (az/value (coupled/explicit-progress task))]
                          (is (= 1 (:rejected report)))
                          (is (zero? (:substeps report)))
                          (is (zero? (:time report)))
                          (is (= 0.0005 (:next-step progress)))
                          (is (= 2 (get-in progress [:last-rejection :reason])))
                          (is (= initial (snapshot)))))
                      (let [report (az/value (coupled/advance-explicit-batch! task (if split? 1 2)))
                            progress (az/value (coupled/explicit-progress task))]
                        (is (= 1 (:substeps report)))
                        (is (= 0.0005 (:time report)))
                        (is (= 2 (:attempts progress)))
                        {:report report :progress progress :state (snapshot)})
                      (finally (coupled/destroy-explicit-task! task)))))))]
    (is (= (run false) (run true)))))

(deftest authored-step-report-accounting
  ;; A known 2 kg momentum budget: gravity contributes -2 N s over 0.1 s,
  ;; the recorded floor impulse contributes +1 N s, and final momentum is -1.
  (let [frame (fn [time center momentum kinetic potential]
                {:time time :volume-ratio 1.0
                 :observation {:mass 2.0 :center center :momentum {:y momentum}
                               :elastic-energy 0.0 :kinetic-energy kinetic
                               :potential-energy potential}})
        run {:scene {:bodies [{:gravity [0.0 -10.0 0.0]}]
                     :bake {:seconds 0.1 :maximum-step 0.001}}
             :solver-version {:source "fixture"}
             :maximum-step 0.001 :frames 2 :nodes [1] :tetrahedra [0]
             :reports [{:substeps 100 :rejected 0 :minimum-jacobian 1.0
                        :maximum-penetration 0.0 :ground-impulse 1.0}]
             :histories [[(frame 0.0 {:x 0.0 :y 1.0 :z 0.0} 0.0 0.0 20.0)
                          (frame 0.1 {:x 0.0 :y 0.975 :z 0.0} -1.0 0.25 19.5)]]
             :final-particles [{:positions-m [[0.0 0.975 0.0]]
                                :velocities-m-s [[0.0 -0.5 0.0]]}]}
        summary (joint/summarize-scene-run run)
        changed (-> run
                    (assoc :maximum-step 0.0005)
                    (assoc-in [:scene :bake :maximum-step] 0.0005)
                    (assoc-in [:histories 0 1 :observation :center :x] 0.003)
                    (update-in [:histories 0 1 :observation :center :y] + 0.004)
                    (assoc-in [:final-particles 0 :positions-m 0] [0.003 0.979 0.0])
                    (assoc-in [:final-particles 0 :velocities-m-s 0] [0.03 -0.46 0.0]))
        comparison (joint/compare-scene-runs run changed)]
    (is (zero? (:vertical-momentum-balance-error-N-s summary)))
    (is (= 20.0 (:initial-energy-J summary) (:maximum-energy-J summary)))
    (is (= 19.75 (:final-energy-J summary)))
    (is (nil? (:vertical-momentum-balance-error-N-s
               (joint/summarize-scene-run (update-in run [:reports 0] dissoc :ground-impulse)))))
    (is (< (abs (- 0.005 (:maximum-center-trajectory-difference-m comparison))) 1.0e-15))
    (is (< (abs (- 0.005 (:maximum-final-node-difference-m comparison))) 1.0e-15))
    (is (< (abs (- 0.05 (:maximum-final-node-velocity-difference-m-s comparison))) 1.0e-15))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same scene"
                         (joint/compare-scene-runs run (assoc-in changed [:scene :bodies 0 :gravity] [0 -9 0]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same solver"
                         (joint/compare-scene-runs run (assoc changed :solver-version {}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identical sample times"
                         (joint/compare-scene-runs run (assoc-in changed [:histories 0 1 :time] 0.2))))))
