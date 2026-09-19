(ns field-lab.variational-test
  (:require [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [field-lab.variational :as implicit]
            [field-lab.coupled-fem :as coupled]
            [field-lab.coupled-job :as joint]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [pitoco.geometry :as geometry]))

(defn near? [expected actual tolerance]
  (<= (abs (- expected actual)) tolerance))

(az/defstruct MassProbe {:layout :extern}
  [[:solved :bool] [:maximum-relative-error :f64] [:first-product [:array 12 :f64]]])

(az/defn probe-mass MassProbe [[workspace [:* implicit/Workspace]] [scale :f64]]
  (let [input (az/field workspace mass-input)
        product (az/field workspace mass-product)
        ^{:var MassProbe} result ak/undefined]
    (dotimes [index (az/field input len)]
      (set! (az/index input index) (* scale (ak/as :f64 (ak/floatFromInt (+ index 1))))))
    (implicit/mass-product! workspace input product)
    (dotimes [index 12]
      (set! (az/index (az/field result first-product) index) (az/index product index)))
    (dotimes [index (az/field input len)]
      (set! (az/index input index) (az/index product index)))
    (az/set-many!
      (az/field result solved) (implicit/solve-mass! workspace input)
      (az/field result maximum-relative-error) 0.0)
    (dotimes [index (az/field input len)]
      (let [expected (ak/as :f64 (ak/floatFromInt (+ index 1)))
            actual (/ (az/index (az/field workspace mass-solution) index) scale)]
        (set! (az/field result maximum-relative-error)
              (ak/max (az/field result maximum-relative-error) (/ (ak/abs (- actual expected)) expected)))))
    result))

(deftest consistent-mass-product-inverse-and-spin-energy
  (let [points [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
        description {:mesh {:points points :cells [[0 1 2 3]]}
                     :material {:young-Pa 1000.0 :poisson-ratio 0.3}
                     :density-kg-m3 120.0 :gravity [0.0 0.0 0.0] :floor? false :friction 0.0
                     :initial-velocities (mapv (fn [[x y _]] [(* -2.0 (- y 0.25)) (* 2.0 (- x 0.25)) 0.0]) points)}]
    (joint/with-system! [description]
      (fn [assembly [state] _]
        (implicit/with-context! assembly {:mass-model :consistent}
          (fn [workspace]
            (doseq [scale [1e-6 1.0 1e6]]
              (let [probe (az/value (probe-mass workspace scale))]
                (is (:solved probe))
                (is (< (:maximum-relative-error probe) 1e-11))
                ;; Mc has unit off-diagonals and diagonal 2 for this tet.
                (is (every? #(< (abs %) 1e-10)
                            (map - (map #(/ % scale) (:first-product probe))
                                 [23.0 28.0 33.0 26.0 31.0 36.0 29.0 34.0 39.0 32.0 37.0 42.0])))))
            (is (near? 3.0 (:kinetic-energy (az/value (implicit/body-observables! workspace 0))) 1e-12))
            (is (near? 15.0 (:kinetic-energy (az/value (dynamics/evaluate! state))) 1e-12))))))
    (joint/with-system! [(joint/sphere {:refinement 1 :center [0.0 1.0 0.0] :density 3.0})
                         (joint/sphere {:refinement 0 :center [1.0 1.0 0.0] :density 4000.0})]
      (fn [assembly _ _]
        (implicit/with-context! assembly {:mass-model :consistent}
          (fn [workspace]
            (let [probe (az/value (probe-mass workspace 1.0))]
              (is (:solved probe))
              (is (< (:maximum-relative-error probe) 1e-9)))))))))

(deftest consistent-mass-resolves-rod-forces-across-axes
  ;; At the first free-flight step, the assembled transverse elastic forces
  ;; are about 1e-26 while the vertical inertial gradient is about 1e-11.
  ;; A global CG norm alone either hides the small axes or stalls its checks.
  (let [mesh (update (geometry/box-mesh [1 32 1] [0.02 0.1 0.02]) :points
                     #(mapv (fn [[x y z]] [(- x 0.01) (+ y 2e-6) (- z 0.01)]) %))
        body {:mesh mesh :material {:young-Pa 1e6 :poisson-ratio 0.0}
              :density-kg-m3 1000.0 :gravity [0.0 0.0 0.0] :floor? true :friction 0.0
              :initial-velocities (vec (repeat (count (:points mesh)) [0.0 -0.01 0.0]))}
        step (/ 0.2 (Math/sqrt 1000.0) 2048.0)]
    (joint/with-system! [body]
      (fn [assembly [state] _]
        (implicit/with-context! assembly {:mass-model :consistent}
          (fn [workspace]
            (let [report (implicit/advance! workspace step step 2.5e-7 1000.0
                                            {:integration :bdf2 :velocity-tolerance 1e-9})
                  observed (az/value (implicit/body-observables! workspace 0))]
              (is (:completed report))
              (is (= 1 (:substeps report)))
              (is (zero? (:rejected report)))
              (is (near? -0.0004 (get-in observed [:momentum :y]) 1e-12)))))))))

(deftest output-boundaries-do-not-create-sliver-steps
  (doseq [limit [1e-7 0.001 1.0]
          ratio [0.25 1.0 (Math/nextUp 1.0) 1.5 (Math/nextDown 2.0) 2.0 3.0]]
    (let [remaining (* ratio limit)
          h (implicit/output-step remaining limit 0.0)
          tail (- remaining h)]
      (is (< 0.0 h (Math/nextUp (min remaining limit))))
      (when (and (pos? tail) (< remaining (* 2.0 limit)))
        (is (>= tail (* 0.5 limit))))))
  (is (= 0.001 (implicit/output-step (+ 0.001 1e-16) 0.001 3.552713678800501e-15)))
  ;; A requested boundary just beyond an integer number of steps used to make
  ;; velocity reconstruction divide coordinate roundoff by a femtosecond step.
  (doseq [integration [:backward-euler :newmark :bdf2]]
    (joint/with-system! [(joint/sphere {:refinement 0 :center [0.0 1.0 0.0]
                                      :velocity [0.2 0.0 0.0] :gravity [0.0 0.0 0.0]})]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [duration (+ 0.002 1e-14)
                  report (implicit/advance! workspace duration 0.001 0.0001 1000.0
                                             {:integration integration :velocity-tolerance 1e-9})
                  observation (az/value (dynamics/evaluate! state))]
              (is (:completed report))
              (is (= duration (:time report)))
              (is (> (:minimum-step report) 0.0004))
              (is (zero? (:rejected report)))
              (is (near? (* 0.2 duration) (get-in observation [:center :x]) 1e-12)))))))))

(deftest native-system-prevents-generation-reload
  (joint/with-system! [(joint/sphere {:refinement 0})]
    (fn [_ _ _]
      (let [attempt (future
                      (try
                        (joint/with-solver-update! (constantly :unexpected))
                        (catch clojure.lang.ExceptionInfo error (ex-message error))))]
        (is (= "A scoped native system still owns this solver generation" (deref attempt 5000 :timeout))))))
  (is (= :available (joint/with-solver-update! (constantly :available)))))

(defn- dilation-reference
  "Independent scalar ODE for a regular tet undergoing uniform dilation.
  Each lumped node has mass rho*V/4; the generalized inertia is 3*rho*V*q²."
  [seconds step]
  (let [young 10000.0
        poisson 0.3
        shear (/ young (* 2.0 (+ 1.0 poisson)))
        mu (* (/ 4.0 3.0) shear)
        lambda (+ (/ (* young poisson) (* (+ 1.0 poisson) (- 1.0 (* 2.0 poisson))))
                  (* (/ 5.0 6.0) shear))
        alpha (+ 1.0 (/ (* 0.75 mu) lambda))
        derivative (fn [[stretch speed]]
                     [speed
                      (- (/ (+ (* mu stretch (- 1.0 (/ 1.0 (+ 1.0 (* 3.0 stretch stretch)))))
                               (* lambda (- (* stretch stretch stretch) alpha) stretch stretch))
                            (* 1000.0 0.1 0.1)))])
        add (fn [a b factor] (mapv #(+ %1 (* factor %2)) a b))]
    (loop [state [1.02 0.0] tick 0]
      (if (= tick (long (Math/round (/ seconds step))))
        state
        (let [a (derivative state)
              b (derivative (add state a (* 0.5 step)))
              c (derivative (add state b (* 0.5 step)))
              d (derivative (add state c step))]
          (recur (add state (mapv #(+ %1 (* 2.0 %2) (* 2.0 %3) %4) a b c d) (/ step 6.0))
                 (inc tick)))))))

(deftest consistent-mass-dilation-matches-independent-reference
  (let [points [[0.1 0.1 0.1] [0.1 -0.1 -0.1] [-0.1 0.1 -0.1] [-0.1 -0.1 0.1]]
        description {:mesh {:points points :cells [[0 2 1 3]]}
                     :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 1000.0
                     :initial-positions (mapv #(mapv (partial * 1.02) %) points)
                     :gravity [0.0 0.0 0.0] :floor? false :friction 0.0}
        ;; A zero-mean tet dilation has Mc=Ml/5, hence its clock is sqrt(5)
        ;; faster than the independently integrated diagonal-mass scalar ODE.
        clock (Math/sqrt 5.0)
        reference-time (* 0.02 clock)
        reference (dilation-reference reference-time (/ reference-time 4000.0))
        fine-reference (dilation-reference reference-time (/ reference-time 8000.0))]
    (is (every? #(< (abs %) 1e-10) (map - reference fine-reference)))
    (joint/with-compiled-kernel! :ipc
      (fn []
        (doseq [integration [:backward-euler :newmark :bdf2]]
          (let [results
                (mapv (fn [step]
                        (joint/with-system! [description]
                          (fn [assembly [state] _]
                            (implicit/with-context! assembly {:mass-model :consistent}
                              (fn [workspace]
                                (let [report (implicit/advance! workspace 0.02 step 0.0001 1000.0
                                                                 {:integration integration :velocity-tolerance 1e-10})
                                      point (az/value (dynamics/position state 0))
                                      velocity (az/value (dynamics/particle-velocity state 0))]
                                  (is (:completed report))
                                  (is (zero? (:rejected report)))
                                  (is (false? (implicit/configure-mass! workspace false)))
                                  (Math/hypot (- (/ (:x point) 0.1) (first reference))
                                              (/ (- (/ (:x velocity) 0.1) (* clock (second reference))) 100.0))))))))
                      [0.002 0.001 0.0005])]
            (is (every? #(> % (if (= integration :backward-euler) 1.7 3.3))
                        (map / results (rest results))))))))))

(deftest newmark-second-order-dilation-and-energy
  (let [points [[0.1 0.1 0.1] [0.1 -0.1 -0.1] [-0.1 0.1 -0.1] [-0.1 -0.1 0.1]]
        description {:mesh {:points points :cells [[0 2 1 3]]}
                     :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 1000.0
                     :initial-positions (mapv #(mapv (partial * 1.02) %) points)
                     :initial-velocities (vec (repeat 4 [0.0 0.0 0.0]))
                     :gravity [0.0 0.0 0.0] :floor? false :friction 0.0}
        reference (dilation-reference 0.04 0.00001)
        finer-reference (dilation-reference 0.04 0.000005)
        run (fn [integration step]
              (joint/with-system! [description]
                (fn [assembly [state] _]
                  (implicit/with-context! assembly
                    (fn [workspace]
                      (let [initial (:elastic-energy (az/value (dynamics/evaluate! state)))
                            report (implicit/advance! workspace 0.04 step 0.0001 1000.0
                                                       {:integration integration :velocity-tolerance 1.0e-10})
                            final (az/value (dynamics/evaluate! state))
                            point (az/value (dynamics/position state 0))
                            velocity (az/value (dynamics/particle-velocity state 0))]
                        (is (:completed report))
                        (is (zero? (:rejected report)))
                        {:state [(/ (:x point) 0.1) (/ (:x velocity) 0.1)]
                         :relative-energy-error (/ (abs (- (+ (:elastic-energy final) (:kinetic-energy final)) initial))
                                                   initial)}))))))
        error (fn [result]
                (let [[stretch speed] (mapv - (:state result) reference)]
                  (Math/hypot stretch (/ speed 50.0))))]
    (is (every? #(< (abs %) 1.0e-10) (map - reference finer-reference)))
    (joint/with-compiled-kernel! :ipc
      (fn []
        (let [newmark (mapv #(run :newmark %) [0.004 0.002 0.001])
              backward (run :backward-euler 0.001)
              errors (mapv error newmark)]
          (is (every? #(> % 3.5) (map / errors (rest errors))))
          (is (< (last errors) (/ (error backward) 10.0)))
          (is (< (:relative-energy-error (last newmark))
                 (/ (:relative-energy-error backward) 10.0)))
          (spit "build/newmark-dilation-evidence.edn"
                (pr-str {:reference reference :finer-reference finer-reference
                         :newmark newmark :backward-euler backward :phase-errors errors})))))))

(deftest newmark-ballistic-motion-and-rejected-step-rollback
  (let [description (joint/sphere {:refinement 0 :center [0.0 1.0 0.0] :velocity [0.2 0.3 -0.1]
                                   :gravity [0.0 -9.81 0.0]})]
    (joint/with-compiled-kernel! :ipc
      (fn []
        (joint/with-system! [description]
          (fn [assembly [state] _]
            (implicit/with-context! assembly
              (fn [workspace]
                (let [before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s])]
                  (is (thrown? clojure.lang.ExceptionInfo
                               (implicit/advance! workspace 0.025 0.01 0.0001 1000.0
                                                  {:integration :newmark :maximum-newton-iterations 1})))
                  (is (= before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s]))))
                (let [report (implicit/advance! workspace 0.025 0.01 0.0001 1000.0 {:integration :newmark})
                      observation (az/value (dynamics/evaluate! state))]
                  (is (:completed report))
                  (is (near? (+ 1.0 (* 0.3 0.025) (* -0.5 9.81 0.025 0.025))
                             (get-in observation [:center :y]) 1.0e-11))
                  (is (near? (- 0.3 (* 9.81 0.025))
                             (/ (get-in observation [:momentum :y]) (:mass observation)) 1.0e-10)))))))))))

(deftest bdf2-variable-step-polynomial-exactness
  ;; A quadratic interpolant has its exact derivative at the new endpoint.
  ;; This oracle checks the coefficients without using the FEM update itself.
  (doseq [previous [0.001 0.1 1.0]
          ratio [0.25 0.5 1.0 2.0]]
    (let [h (* previous ratio)
          {:keys [effective-step history-weight]}
          (az/value (implicit/time-coefficients h previous 2))
          polynomial (fn [time] (+ (* 0.7 time) (* 1.3 time time)))
          current-increment (- (polynomial h) (polynomial 0.0))
          previous-increment (- (polynomial 0.0) (polynomial (- previous)))
          derivative (/ (- current-increment (* history-weight previous-increment))
                        effective-step)]
      (is (near? (+ 0.7 (* 2.6 h)) derivative 1.0e-12))
      (is (near? h (+ effective-step (* history-weight previous)) 1.0e-14))))
  (is (= {:effective-step 0.001 :history-weight 0.0}
         (az/value (implicit/time-coefficients 0.001 0.0 2)))))

(deftest bdf2-dilation-order-and-accepted-history
  (let [points [[0.1 0.1 0.1] [0.1 -0.1 -0.1] [-0.1 0.1 -0.1] [-0.1 -0.1 0.1]]
        description {:mesh {:points points :cells [[0 2 1 3]]}
                     :material {:young-Pa 10000.0 :poisson-ratio 0.3} :density-kg-m3 1000.0
                     :initial-positions (mapv #(mapv (partial * 1.02) %) points)
                     :initial-velocities (vec (repeat 4 [0.0 0.0 0.0]))
                     :gravity [0.0 0.0 0.0] :floor? false :friction 0.0}
        reference (dilation-reference 0.04 0.000005)
        run (fn [step intervals inject-failure?]
              (joint/with-system! [description]
                (fn [assembly [state] _]
                  (implicit/with-context! assembly
                    (fn [workspace]
                      (let [initial (:elastic-energy (az/value (dynamics/evaluate! state)))
                            reports
                            (mapv (fn [index seconds]
                                    (when (and inject-failure? (= index 1))
                                      (let [before (job/snapshot state 4)]
                                        (is (thrown? clojure.lang.ExceptionInfo
                                                     (implicit/advance! workspace seconds step 0.0001 1000.0
                                                                        {:integration :bdf2
                                                                         :maximum-newton-iterations 1})))
                                        (is (= before (job/snapshot state 4)))))
                                    (implicit/advance! workspace seconds step 0.0001 1000.0
                                                       {:integration :bdf2 :velocity-tolerance 1.0e-10}))
                                  (range) intervals)
                            final (az/value (dynamics/evaluate! state))
                            point (az/value (dynamics/position state 0))
                            velocity (az/value (dynamics/particle-velocity state 0))]
                        (is (every? :completed reports))
                        (is (every? #(zero? (:rejected %)) reports))
                        {:state [(/ (:x point) 0.1) (/ (:x velocity) 0.1)]
                         :energy-error (/ (abs (- (+ (:elastic-energy final) (:kinetic-energy final)) initial)) initial)
                         :accepted-steps (reduce + (map :substeps reports))}))))))
        error (fn [result]
                (let [[stretch speed] (mapv - (:state result) reference)]
                  (Math/hypot stretch (/ speed 50.0))))]
    (joint/with-compiled-kernel! :ipc
      (fn []
        (let [runs (mapv #(run % [0.04] false) [0.002 0.001 0.0005])
              split (run 0.001 (vec (repeat 10 0.004)) false)
              retried (run 0.001 (vec (repeat 10 0.004)) true)
              errors (mapv error runs)]
          (is (every? #(> % 3.5) (map / errors (rest errors))))
          (is (= 40 (:accepted-steps split) (:accepted-steps retried)))
          (is (every? #(< (abs %) 1.0e-9) (map - (:state (second runs)) (:state split))))
          (is (= split retried))
          (spit "build/bdf2-dilation-evidence.edn"
                (pr-str {:reference reference :runs runs :phase-errors errors
                         :split split :after-rejected-attempt retried})))))))

(deftest bdf2-variable-step-ballistics-and-history-reset
  (joint/with-system!
    [(joint/sphere {:refinement 0 :center [0.0 1.0 0.0] :velocity [0.2 0.3 -0.1]
                    :gravity [0.0 -9.81 0.0]})]
    (fn [assembly [state] _]
      (implicit/with-context! assembly
        (fn [workspace]
          (let [reports (mapv (fn [[seconds cap]]
                                (implicit/advance! workspace seconds cap 0.0001 1000.0
                                                   {:integration :bdf2 :velocity-tolerance 1.0e-10}))
                              [[0.001 0.001] [0.006 0.004] [0.001 0.001] [0.010 0.010]])
                final (az/value (dynamics/evaluate! state))
                time 0.018
                velocity (/ (get-in final [:momentum :y]) (:mass final))]
            (is (every? :completed reports))
            ;; The final requested 10 ms step grows from 1 ms through 2, 4, 4 ms.
            (is (= [1 2 1 3] (mapv :substeps reports)))
            (is (near? (- 0.3 (* 9.81 time)) velocity 1.0e-9))
            (is (near? (+ 1.0 (* 0.3 time) (* -0.5 9.81 time time))
                       (get-in final [:center :y]) 3.0e-5))
            (implicit/reset-history! workspace)
            (let [report (implicit/advance! workspace 0.004 0.004 0.0001 1000.0
                                            {:integration :bdf2 :velocity-tolerance 1.0e-10})
                  restarted (az/value (dynamics/evaluate! state))]
              (is (= 1 (:substeps report)))
              ;; Explicit reset deliberately starts a fresh backward-Euler step.
              (is (near? (+ (get-in final [:center :y]) (* 0.004 velocity) (* -9.81 0.004 0.004))
                         (get-in restarted [:center :y]) 1.0e-10)))))))))

(deftest compiled-implicit-kernel-lifetime-and-cancellation
  (joint/with-system! [(joint/sphere {:refinement 0 :velocity [1.0 0.0 0.0]})]
    (fn [assembly [state] _]
      (implicit/with-context! assembly
        (fn [workspace]
          (let [escaped
                (joint/with-compiled-kernel! :ipc
                  (fn []
                    (let [cancel (atom false)
                          failure (try
                                    (implicit/advance! workspace 0.001 0.0001 0.0001 1000.0
                                                       {:maximum-attempts 1 :cancelled? #(deref cancel)
                                                        :on-progress (fn [_] (reset! cancel true))})
                                    nil
                                    (catch clojure.lang.ExceptionInfo error (ex-data error)))]
                      (is (:cancelled? failure))
                      (is (= 1 (get-in failure [:report :substeps])))
                      (is (= 0.0001 (get-in failure [:report :time])))
                      (is (= :ipc (:contact-method joint/*kernel-provenance*))))
                    (let [report (implicit/advance! workspace 0.0009 0.0001 0.0001 1000.0)]
                      (is (:completed report))
                      (is (near? 0.001 (get-in (az/value (dynamics/evaluate! state)) [:center :x]) 1.0e-11)))
                    (with-redefs [implicit/advance-batch! (fn [& _] (throw (ex-info "Unexpected fallback" {})))]
                      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Solver changed"
                                           (implicit/advance! workspace 0.001 0.0001 0.0001 1000.0))))
                    implicit/*advance-batch*))
                before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s])
                task (implicit/create-task! workspace 0.001 0.0001)]
            (try
              (is (thrown? IllegalStateException (escaped workspace task 0.0001 1000.0 1.0e-7 100 1)))
              (is (= before (select-keys (job/snapshot state 43) [:positions-m :velocities-m-s])))
              (finally (implicit/destroy-task! task)))))))))

(deftest authored-nonlinear-tolerance-is-enforced
  (let [source {:format :pitoco/solid-scene-v1
                :bodies [(joint/sphere {:refinement 0 :center [0.0 1.0 0.0] :gravity [0.0 -9.81 0.0]})]
                :bake {:seconds (/ 1.0 240.0) :maximum-step 0.001 :contact-method :ipc
                       :velocity-tolerance 1.0e-8 :maximum-newton-iterations 120
                       :integration :newmark}}]
    (doseq [[field value] [[:velocity-tolerance 0.0] [:velocity-tolerance ##NaN]
                         [:maximum-newton-iterations 0] [:maximum-newton-iterations 1.5]
                         [:integration :unknown] [:integration nil]]]
      (is (thrown? clojure.lang.ExceptionInfo (joint/normalize-scene (assoc-in source [:bake field] value)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require :contact-method :ipc"
                         (joint/normalize-scene (assoc-in source [:bake :contact-method] :discrete))))
    (joint/with-compiled-kernel! :ipc
      (fn []
        (let [run (first (joint/scene-step-study! source [0.001]))
              summary (joint/summarize-scene-run run)]
          (is (= 1.0e-8 (get-in run [:scene :bake :velocity-tolerance])))
          (is (= :newmark (get-in run [:scene :bake :integration])))
          (is (every? #(<= (:residual %) 1.0e-8) (:reports run)))
          ;; An absent penetration measurement must never be presented as zero.
          (is (nil? (:maximum-sampled-penetration-m summary)))
          (is (<= (:maximum-output-solve-residual-m-s summary) 1.0e-8)))))))

(deftest implicit-study-preserves-completed-cases
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "pitoco-study-" (make-array java.nio.file.attribute.FileAttribute 0)))
        output (clojure.java.io/file directory "study.edn")
        cancelled (atom false)
        source {:format :pitoco/solid-scene-v1
                :bodies [(joint/sphere {:refinement 0 :velocity [0.1 0.0 0.0]})]
                :bake {:seconds (/ 1.0 240.0) :contact-method :ipc}}
        options {:output (.getPath output) :execution :snapshot :maximum-steps [0.001 0.0005]
                 :cancelled? #(deref cancelled)
                 :on-result (fn [_] (reset! cancelled true))}]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
                           (joint/write-scene-study! source options)))
      (let [saved (slurp output)
            record (clojure.edn/read-string saved)]
        (is (= :cancelled (:status record)))
        (is (true? (get-in record [:failure :cancelled?])))
        (is (= 1 (count (:runs record))))
        (is (= 2 (get-in record [:runs 0 :frames])))
        (is (= :ipc (get-in record [:runs 0 :solver-version 'field-lab.coupled-job/kernel :contact-method])))
        (is (thrown? java.nio.file.FileAlreadyExistsException (joint/write-scene-study! source options)))
        (is (= saved (slurp output))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete ^java.io.File file))))))

(az/defstruct ProjectionProbe {:layout :extern}
  [[:valid :bool] [:matrix [:array 144 :f64]]])

(az/defn project-block ProjectionProbe [[input [:array 144 :f64]]]
  (let [^:var matrix input
        valid (implicit/project-element! (ak/& (az/index matrix 0)))]
    (ProjectionProbe {:valid valid :matrix matrix})))

(az/defn main :void
  "Standalone projection/link smoke check; no JVM or development dispatch." []
  (let [^{:var [:array 144 :f64]} matrix ak/undefined]
    (dotimes [row 12]
      (dotimes [column 12]
        (set! (az/index matrix (+ (* 12 row) column)) (if (ak/== row column) 1.0 0.0))))
    (az/set-many! (az/index matrix 1) 2.0 (az/index matrix 12) 2.0)
    (debug/assert (implicit/project-element! (ak/& (az/index matrix 0))))
    ;; The 2x2 block has eigenvalues 3 and -1: its PSD projection is all 1.5.
    (dotimes [row 12]
      (dotimes [column 12]
        (let [expected (ak/as :f64 (if (and (< row 2) (< column 2)) 1.5
                                      (if (ak/== row column) 1.0 0.0)))]
          (debug/assert (< (ak/abs (- (az/index matrix (+ (* 12 row) column)) expected)) 1.0e-12)))))))

(deftest element-projection-matches-known-spectrum
  ;; Dense Householder eigenvectors exercise storage order and reconstruction.
  ;; The exact projection follows from the chosen spectrum, independently of
  ;; the eigensolver used by the implementation.
  (let [norm (Math/sqrt (reduce + (map #(* % %) (range 1 13))))
        direction (mapv #(/ % norm) (range 1 13))
        q (fn [row column] (- (if (= row column) 1.0 0.0)
                              (* 2.0 (direction row) (direction column))))
        from-spectrum (fn [spectrum]
                        (vec (for [row (range 12) column (range 12)]
                               (reduce + (for [mode (range 12)]
                                           (* (q row mode) (spectrum mode) (q column mode)))))))
        input (from-spectrum (vec (range -6.0 6.0)))
        expected (from-spectrum (mapv #(max 0.0 %) (range -6.0 6.0)))
        perturbed (-> input (update 1 + 0.3) (update 12 - 0.3))]
    (doseq [scale [1.0e-12 1.0 1.0e12] matrix [input perturbed]]
      (let [result (az/value (project-block (mapv #(* scale %) matrix)))]
        (is (:valid result))
        (is (< (apply max (map #(abs (- (* scale %1) %2)) expected (:matrix result)))
               (* scale 1.0e-12)))
        (is (every? true? (for [row (range 12) column (range 12)]
                           (= (get-in result [:matrix (+ (* 12 row) column)])
                              (get-in result [:matrix (+ (* 12 column) row)])))))))))

(deftest nonfinite-projection-input-is-not-mutated
  (doseq [bad [Double/NaN Double/POSITIVE_INFINITY]]
    (let [input (assoc (vec (repeat 144 0.0)) 3 bad)
          result (az/value (project-block input))]
      (is (false? (:valid result)))
      (is (= (dissoc (zipmap (range 144) input) 3)
             (dissoc (zipmap (range 144) (:matrix result)) 3)))
      (is (if (Double/isNaN bad) (Double/isNaN (get-in result [:matrix 3]))
              (= bad (get-in result [:matrix 3])))))))

(deftest implicit-free-flight-and-common-clock
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                   :center [0.0 1.0 0.0] :velocity [0.3 0.2 -0.1]
                                   :gravity [0.0 -9.81 0.0]})
        duration 0.01
        step 0.001]
    (joint/with-system! [description]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [before (az/value (dynamics/evaluate! state))
                  report (implicit/advance! workspace duration step 1.0e-4 1000.0)
                  after (az/value (dynamics/evaluate! state))]
              (is (:completed report))
              (is (= 10 (:substeps report)))
              (is (= duration (:time report)))
              (is (zero? (:contact-energy report)))
              (is (near? 1.0 (:minimum-jacobian report) 1.0e-10))
              (doseq [[axis velocity gravity] [[:x 0.3 0.0] [:y 0.2 -9.81] [:z -0.1 0.0]]]
                (is (near? (+ (get-in before [:center axis]) (* duration velocity)
                              (* 0.5 gravity (+ (* duration duration) (* duration step))))
                           (get-in after [:center axis]) 1.0e-11))
                (is (near? (+ velocity (* gravity duration))
                           (/ (get-in after [:momentum axis]) (:mass after)) 1.0e-10))))))))))

(deftest rejected-newton-step-restores-state
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                   :gravity [0.0 -9.81 0.0]})]
    (joint/with-system! [description]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [before (job/snapshot state (count (get-in description [:mesh :points])))
                  report (az/value (implicit/advance-native! workspace 0.001 0.001 1.0e-4 1000.0 1.0e-7 1))
                  after (job/snapshot state (count (get-in description [:mesh :points])))]
              (is (false? (:completed report)))
              (is (= 5 (:status report)))
              (is (zero? (:time report)))
              (is (= before after))
              (is (:completed (implicit/advance! workspace 0.001 0.001 1.0e-4 1000.0))))))))))

(deftest bounded-advance-preserves-target-and-accepted-state
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                   :center [0.0 1.0 0.0] :velocity [0.3 0.2 -0.1]
                                   :gravity [0.0 -9.81 0.0]})
        simulate
        (fn [budget]
          (joint/with-system! [description]
            (fn [assembly [state] _]
              (implicit/with-context! assembly
                (fn [workspace]
                  (let [task (implicit/create-task! workspace 0.01 0.001)]
                    (try
                      (loop [calls 0 previous 0.0]
                        (is (< calls 12) "This ten-step solve must finish within its work bound")
                        (when (>= calls 12) (throw (ex-info "Batch did not progress" {})))
                        (let [report (az/value (implicit/advance-batch! workspace task
                                                                       0.0001 1000.0 1.0e-7 100 budget))]
                          (is (<= previous (:time report)))
                          (if (:completed report)
                            {:report report :snapshot (job/snapshot state 43)}
                            (do
                              (is (= 6 (:status report)))
                              (is (= (* (inc calls) budget) (:substeps report)))
                              (recur (inc calls) (:time report))))))
                      (finally (implicit/destroy-task! task)))))))))
        uninterrupted (simulate 100)
        batched (simulate 1)]
    (is (= (:report uninterrupted) (:report batched)))
    (is (= (:snapshot uninterrupted) (:snapshot batched)))
    (is (= 0.01 (get-in batched [:report :time])))))

(deftest rejected-attempts-consume-budget-and-retain-reduced-step
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                   :gravity [0.0 0.0 0.0]})
        points (get-in description [:mesh :points])
        stretched (assoc description :initial-positions
                         (mapv (fn [[x y z]] [(* 1.15 x) y z]) points))]
    (joint/with-system! [stretched]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [task (implicit/create-task! workspace 0.01 0.01)
                  before (job/snapshot state 43)]
              (try
                (dotimes [attempt 2]
                  (let [report (az/value (implicit/advance-batch! workspace task
                                                                 0.0001 1000.0 1.0e-7 2 1))]
                    (is (= 6 (:status report)))
                    (is (= (inc attempt) (:rejected report)))
                    (is (zero? (:substeps report)))
                    (is (zero? (:time report)))
                    (is (= (/ 0.01 (Math/pow 2.0 (inc attempt))) (implicit/task-step-cap task)))
                    (is (= before (job/snapshot state 43)))))
                (finally (implicit/destroy-task! task))))))))))

(deftest cancelled-host-bake-preserves-state
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0})]
    (joint/with-system! [description]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [before (job/snapshot state 43)
                  thread (Thread/currentThread)]
              (try
                (.interrupt thread)
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bake cancelled"
                                     (implicit/advance! workspace 0.01 0.001 0.0001 1000.0)))
                (finally (Thread/interrupted)))
              (is (= before (job/snapshot state 43)))
              (is (:completed (implicit/advance! workspace 0.001 0.001 0.0001 1000.0))))))))))

(deftest cancellation-after-progress-keeps-accepted-prefix
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                   :center [0.0 1.0 0.0] :gravity [0.0 -9.81 0.0]})]
    (joint/with-system! [description]
      (fn [assembly [state] _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [cancelled (atom false)
                  progress (atom [])
                  failure (try
                            (implicit/advance! workspace 0.01 0.001 0.0001 1000.0
                                               {:cancelled? #(deref cancelled)
                                                :on-progress #(do (swap! progress conj %)
                                                                  (reset! cancelled true))})
                            nil
                            (catch clojure.lang.ExceptionInfo error (ex-data error)))]
              (is (:cancelled? failure))
              (is (= 1 (count @progress)))
              (is (= 8 (get-in failure [:report :substeps])))
              (is (near? 0.008 (get-in failure [:report :time]) 1.0e-14))
              (is (= (peek @progress) (:report failure)))
              (let [observation (:observables (job/snapshot state 43))]
                (is (near? -0.07848 (/ (get-in observation [:momentum :y]) (:mass observation))
                           1.0e-10)))
              (is (:completed (implicit/advance! workspace 0.002 0.001 0.0001 1000.0))))))))))

(deftest implicit-head-on-contact
  (let [descriptions (mapv (fn [[center velocity]]
                            (joint/sphere {:center center :velocity velocity :refinement 0 :geometry :polyhedron}))
                          [[[-0.0525 0.0 0.0] [1.0 0.0 0.0]]
                           [[0.0525 0.0 0.0] [-1.0 0.0 0.0]]])]
    (joint/with-compiled-kernel! :ipc
      (fn []
        (doseq [integration [:backward-euler :newmark :bdf2]]
          (testing (str "Two-body contact with " integration)
            (joint/with-system! descriptions
              (fn [assembly states _]
                (implicit/with-context! assembly
                  (fn [workspace]
                    (let [before (az/value (coupled/observe! assembly))
                          reports (mapv (fn [_]
                                          (implicit/advance! workspace 0.0001 0.0001 1.0e-4 1000.0
                                                             {:integration integration}))
                                        (range 120))
                          after (az/value (coupled/observe! assembly))]
                      (is (every? :completed reports))
                      (is (near? 0.012 (:time (last reports)) 1.0e-14))
                      (is (some #(pos? (:contact-energy %)) reports))
                      (is (every? #(pos? (:minimum-jacobian %)) reports))
                      (doseq [axis [:x :y :z]]
                        (is (near? (get-in before [:momentum axis]) (get-in after [:momentum axis]) 1.0e-8))
                        (is (near? 0.0 (reduce + (map #(get-in % [:contact-impulse axis]) reports)) 1.0e-10)))
                      ;; Backward Euler dissipates energy here. Newmark does not
                      ;; promise monotone energy for nonlinear contact.
                      (when (= integration :backward-euler)
                        (is (<= (+ (:kinetic-energy after) (:elastic-energy after) (:contact-energy (last reports)))
                                (+ (:kinetic-energy before) (:elastic-energy before) 1.0e-7)))))))))))))))

(deftest implicit-ground-friction
  (doseq [integration [:backward-euler :newmark :bdf2]]
    (testing (str "Friction with " integration)
      (let [results
            (mapv
             (fn [friction]
               (let [description (joint/sphere {:geometry :polyhedron :refinement 0
                                                :center [0.0 0.05005 0.0] :velocity [0.2 0.0 0.0]
                                                :gravity [0.0 -9.81 0.0] :floor? true :friction friction})]
                 (joint/with-system! [description]
                   (fn [assembly [state] _]
                     (implicit/with-context! assembly
                       (fn [workspace]
                         (let [failure (try
                                         (implicit/advance! workspace 0.0001 0.0001 0.0001 1000.0
                                                            {:integration integration :maximum-newton-iterations 1})
                                         nil
                                         (catch clojure.lang.ExceptionInfo error (ex-data error)))
                               _ (is (= 0.0 (:ground-impulse failure)))
                               _ (is (= {:x 0.0 :y 0.0 :z 0.0} (:contact-impulse failure)))
                               before (az/value (dynamics/evaluate! state))
                               reports (mapv (fn [_] (implicit/advance! workspace 0.0001 0.0001 0.0001 1000.0
                                                                     {:integration integration})) (range 20))
                               report (last reports)
                               after (az/value (dynamics/evaluate! state))
                               impulse (into {} (for [axis [:x :y :z]]
                                                  [axis (reduce + (map #(get-in % [:contact-impulse axis]) reports))]))]
                           (is (:completed report))
                           (is (pos? (:minimum-jacobian report)))
                           (is (pos? (:y impulse)))
                           (doseq [axis [:x :y :z]]
                             (is (near? (- (get-in after [:momentum axis]) (get-in before [:momentum axis]))
                                        (+ (axis impulse)
                                           (if (= axis :y) (* -9.81 0.002 (:mass before)) 0.0))
                                        1.0e-8)))
                           (is (= (:y impulse) (reduce + (map :ground-impulse reports))))
                           (assoc after :reports reports :report report))))))))
             [0.0 0.5])
            speed (fn [observation] (/ (get-in observation [:momentum :x]) (:mass observation)))]
        (is (near? 0.2 (speed (first results)) 1.0e-9))
        (is (< (speed (second results)) (- (speed (first results)) 1.0e-5)))
        (is (every? #(pos? (:minimum-height %)) results))
        (is (some #(pos? (:friction-energy %)) (:reports (second results))))))))

(deftest actual-initial-shape-is-validated
  (let [description (joint/sphere {:geometry :polyhedron :refinement 0})
        inverted (assoc description :initial-positions
                        (assoc (get-in description [:mesh :points]) 0 [0.0 0.2 0.0]))]
    (joint/with-system! [inverted]
      (fn [assembly _ _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unable to initialize IPC contact"
                             (implicit/with-context! assembly (constantly :unexpected))))))))


(deftest authored-ipc-bake-has-native-provenance
  (let [source {:format :pitoco/solid-scene-v1 :title "IPC regression"
                :bodies [(joint/sphere {:geometry :polyhedron :refinement 0
                                       :center [0.0 1.0 0.0] :gravity [0.0 -9.81 0.0]})]
                :bake {:seconds (/ 2.0 240.0) :maximum-step 0.0001 :contact-method :ipc
                       :clearance 0.0001 :barrier-pressure 1000.0}}
        result (joint/bake-scene! source)
        owned (:group result)]
    (try
      (is (group/complete? owned))
      (is (= 3 (:frames result)))
      (is (= :ipc (get-in result [:scene :bake :contact-method])))
      (is (every? :completed (:reports result)))
      (is (re-matches #"[0-9a-f]{64}"
                      (get-in result [:solver-version 'field-lab.variational/native-library :library-sha256])))
      (is (= (:solver-version result) (joint/solver-version :ipc)))
      (is (near? (/ 2.0 240.0) (:time (az/value (cache/frame-info (group/item owned 0) 2))) 1.0e-14))
      (finally (group/destroy! owned)))))
