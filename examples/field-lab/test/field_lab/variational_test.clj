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
            [field-lab.mesh-group :as group]))

(defn near? [expected actual tolerance]
  (<= (abs (- expected actual)) tolerance))

(az/defstruct ProjectionProbe {:layout :extern}
  [[:valid :bool] [:matrix [:array 144 :f64]]])

(az/defn project-block
  :- ProjectionProbe [[input [:array 144 :f64]]]
  (let [^:var matrix input
        valid (implicit/project-element! (ak/& (az/index matrix 0)))]
    (ProjectionProbe {:valid valid :matrix matrix})))

(az/defn main
  "Standalone projection/link smoke check; no JVM or development dispatch."
  :- :void []
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
    (joint/with-system! descriptions
      (fn [assembly states _]
        (implicit/with-context! assembly
          (fn [workspace]
            (let [before (az/value (coupled/observe! assembly))
                  reports (mapv (fn [_] (implicit/advance! workspace 0.001 0.0001 1.0e-4 1000.0)) (range 12))
                  after (az/value (coupled/observe! assembly))]
              (is (every? :completed reports))
              (is (near? 0.012 (:time (last reports)) 1.0e-14))
              (is (some #(pos? (:contact-energy %)) reports))
              (is (every? #(pos? (:minimum-jacobian %)) reports))
              (doseq [axis [:x :y :z]]
                (is (near? (get-in before [:momentum axis]) (get-in after [:momentum axis]) 1.0e-8)))
              (is (<= (+ (:kinetic-energy after) (:elastic-energy after) (:contact-energy (last reports)))
                      (+ (:kinetic-energy before) (:elastic-energy before) 1.0e-7))))))))))

(deftest implicit-ground-friction
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
                     (let [reports (mapv (fn [_] (implicit/advance! workspace 0.0001 0.0001 0.0001 1000.0)) (range 20))
                           report (last reports)]
                       (is (:completed report))
                       (is (pos? (:minimum-jacobian report)))
                       (assoc (az/value (dynamics/evaluate! state)) :reports reports :report report))))))))
         [0.0 0.5])
        speed (fn [observation] (/ (get-in observation [:momentum :x]) (:mass observation)))]
    (is (near? 0.2 (speed (first results)) 1.0e-9))
    (is (< (speed (second results)) (- (speed (first results)) 1.0e-5)))
    (is (every? #(pos? (:minimum-height %)) results))
    (is (some #(pos? (:friction-energy %)) (:reports (second results))))))

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
