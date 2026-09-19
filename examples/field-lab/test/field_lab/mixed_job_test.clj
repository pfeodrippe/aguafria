(ns field-lab.mixed-job-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.mixed-job :as job]
            [field-lab.mixed-solver-test :as solver]
            [field-lab.mixed-tetra-test :as fixture]))

(def simplex {:mesh {:points fixture/reference :cells [[0 1 2 3]]}
              :density-kg-m3 120.0 :gravity [0.0 0.0 0.0]})

(def two-cell
  {:mesh {:points (conj fixture/reference [1.0 1.0 1.0]) :cells [[0 1 2 3] [1 2 3 4]]}
   :density-kg-m3 120.0 :floor? true
   :cell-materials [{} {:young-Pa 17000.0 :poisson-ratio 0.2 :density-kg-m3 60.0}]})

(az/defn set-iteration-limit! :void [[context [:* job/Context]] [limit :u32]]
  (set! (az/field (az/field context problem) iteration-limit) limit))

(defn near-vector? [expected actual tolerance]
  (< (fixture/maximum (map - expected (mapv actual [:x :y :z]))) tolerance))

(deftest exact-native-inertia-observables
  (job/with-context! simplex
    (fn [context]
      (let [data (:observables (job/snapshot context))]
        (is (:valid data))
        (is (= 20.0 (:mass data)))
        (is (near-vector? [0.25 0.25 0.25] (:center data) 1e-13))
        (is (= 0.0 (:kinetic data))))))
  (job/with-context! (assoc simplex :initial-velocities (vec (repeat 4 [3.0 4.0 5.0])))
    (fn [context]
      (let [data (az/value (job/observables context))]
        (is (= 500.0 (:kinetic data)))
        (is (near-vector? [60.0 80.0 100.0] (:momentum data) 1e-12))
        (is (near-vector? [5.0 -10.0 5.0] (:angular-momentum data) 1e-12)))))
  (job/with-context! (assoc simplex :initial-velocities
                          (mapv (fn [[x y _]] [(* -2.0 (- y 0.25)) (* 2.0 (- x 0.25)) 0.0]) fixture/reference))
    (fn [context]
      (let [data (az/value (job/observables context))]
        (is (< (abs (- 3.0 (:kinetic data))) 1e-12))
        (is (near-vector? [0.0 0.0 0.0] (:momentum data) 1e-12))
        (is (near-vector? [0.5 0.5 3.0] (:angular-momentum data) 1e-12))))))

(deftest authored-mesh-matches-verified-contact-fixture
  (let [expected (solver/solve-case {:lower solver/floor-lower})]
    (job/with-context! two-cell
      (fn [context]
        (let [report (az/value (job/advance! context 0.01))
              data (job/snapshot context)
              displaced (mapv (fn [rest delta] (mapv + rest (fixture/vector-data delta)))
                              (get-in two-cell [:mesh :points]) (subvec (:x expected) 0 5))]
          (is (= 0 (:status report)) (pr-str report))
          (is (= displaced (:positions data)))
          (is (= 1 (get-in data [:observables :steps])))
          (is (= 0.01 (get-in data [:observables :time])))
          (let [observation (:observables data)
                force (get-in observation [:contact-force :y])
                momentum (get-in observation [:momentum :y])]
            (is (pos? force))
            (is (< (abs (- momentum (* 0.01 (+ force (* 40.0 -9.81))))) 1e-8)))
          (is (= 40.0 (get-in data [:observables :mass]))))))))

(deftest failed-step-preserves-accepted-state-and-can-retry
  (job/with-context! two-cell
    (fn [context]
      (let [before (job/snapshot context)]
        (set-iteration-limit! context 1)
        (is (= 4 (:status (az/value (job/advance! context 0.01)))))
        (is (= before (job/snapshot context)))
        (set-iteration-limit! context 100)
        (is (= 0 (:status (az/value (job/advance! context 0.01)))))
        (let [accepted (job/snapshot context)]
          (doseq [duration [0.0 -1.0 Double/NaN Double/POSITIVE_INFINITY]]
            (is (not= 0 (:status (az/value (job/advance! context duration)))))
            (is (= accepted (job/snapshot context)))))))))

(deftest mesh-input-rejection
  (is (nil? (az/value (job/create! 0 0))))
  (doseq [description [(assoc-in simplex [:mesh :cells] [[0 0 2 3]])
                       (assoc-in simplex [:mesh :cells] [[0 1 2 4]])
                       (update-in simplex [:mesh :points] conj [2.0 2.0 2.0])
                       (assoc simplex :floor? true :floor-height Double/NaN)
                       (assoc simplex :density-kg-m3 -1.0)
                       (assoc simplex :initial-positions (vec (repeat 4 [0.0 0.0 0.0])))]]
    (is (thrown? clojure.lang.ExceptionInfo (job/with-context! description (fn [_] nil))))))

(deftest sdirk-uniform-acceleration-is-second-order-exact
  (let [duration 0.05
        initial-velocity [0.3 0.4 -0.2]
        gravity [0.0 -9.81 0.0]]
    (job/with-context! (assoc simplex :gravity gravity
                              :initial-velocities (vec (repeat 4 initial-velocity)))
      (fn [context]
        (let [report (az/value (job/advance-sdirk! context duration))
              {:keys [positions observables]} (job/snapshot context)
              shift (mapv #(+ (* duration %1) (* 0.5 duration duration %2)) initial-velocity gravity)]
          (is (= 0 (:status report)) (pr-str report))
          (doseq [[rest actual] (map vector fixture/reference positions)]
            (is (< (fixture/maximum (map - actual (mapv + rest shift))) 1e-9)))
          (is (near-vector? (mapv #(* 20.0 (+ %1 (* duration %2))) initial-velocity gravity)
                            (:momentum observables) 1e-8))
          (is (= 1 (:steps observables)))
          (is (= duration (:time observables)))
          (is (= {:x 0.0 :y 0.0 :z 0.0} (:contact-impulse observables))))))))

(deftest sdirk-contact-impulse-and-rollback
  (job/with-context! two-cell
    (fn [context]
      (let [report (az/value (job/advance-sdirk! context 0.01))
            {:keys [positions observables] :as accepted} (job/snapshot context)
            impulse (get-in observables [:contact-impulse :y])]
        (is (= 0 (:status report)) (pr-str report))
        (is (every? #(>= (second %) 0.0) positions))
        (is (pos? impulse))
        (is (< (abs (- (get-in observables [:momentum :y]) (* 0.01 40.0 -9.81) impulse)) 1e-8))
        (set-iteration-limit! context 0)
        (is (not= 0 (:status (az/value (job/advance-sdirk! context 0.01)))))
        (is (= accepted (job/snapshot context)))
        (set-iteration-limit! context 100)
        (is (= 0 (:status (az/value (job/advance-sdirk! context 0.01)))))
        (is (= 2 (get-in (job/snapshot context) [:observables :steps])))))))

(deftest sdirk-second-stage-failure-preserves-initial-state
  (let [duration 0.03
        gamma (- 1.0 (/ 1.0 (Math/sqrt 2.0)))]
    ;; The first stage converges within three iterations on this contact case;
    ;; the second requires more. Verify the actual failure boundary independently.
    (job/with-context! two-cell
      (fn [context]
        (set-iteration-limit! context 3)
        (is (= 0 (:status (az/value (job/advance! context (* gamma duration))))))))
    (job/with-context! two-cell
      (fn [context]
        (set-iteration-limit! context 3)
        (let [before (job/snapshot context)
              report (az/value (job/advance-sdirk! context duration))]
          (is (= 4 (:status report)))
          (is (< 3 (:iterations report) 7))
          (is (= before (job/snapshot context)))
          (set-iteration-limit! context 100)
          (is (= 0 (:status (az/value (job/advance-sdirk! context duration)))))
          (is (= 1 (get-in (job/snapshot context) [:observables :steps]))))))))

(deftest compiled-kernel-preserves-physics-rollback-and-source-guard
  (let [compiled! (requiring-resolve 'field-lab.coupled-job/with-compiled-kernel!)
        configuration (az/configuration)
        run! (fn [integration]
               (job/with-context! two-cell
                 (fn [context]
                   (dotimes [_ 6]
                     (is (= 0 (:status (job/step! context 0.005 integration)))))
                   (let [accepted (job/snapshot context)]
                     (set-iteration-limit! context 0)
                     (is (not= 0 (:status (job/step! context 0.005 integration))))
                     (is (= accepted (job/snapshot context)))
                     (set-iteration-limit! context 100)
                     (is (= 0 (:status (job/step! context 0.005 integration))))
                     (job/snapshot context)))))
        direct (mapv run! [:backward-euler :sdirk2])
        frozen (compiled! :mixed #(mapv run! [:backward-euler :sdirk2]))]
    (is (= configuration (az/configuration)))
    (doseq [[expected actual] (map vector direct frozen)]
      (is (= 7 (get-in actual [:observables :steps])))
      (is (< (fixture/maximum (map - (flatten (:positions expected)) (flatten (:positions actual)))) 1e-10))
      (doseq [quantity [:kinetic :elastic :potential :time]]
        (is (< (abs (- (get-in expected [:observables quantity])
                        (get-in actual [:observables quantity]))) 1e-8)))
      (doseq [quantity [:momentum :contact-force :contact-impulse]]
        (is (near-vector? (mapv (get-in expected [:observables quantity]) [:x :y :z])
                          (get-in actual [:observables quantity]) 1e-8))))
    (compiled! :mixed
      #(job/with-context! two-cell
         (fn [context]
           (let [before (job/snapshot context)]
             (with-redefs [job/advance! (fn [& _] nil)]
               (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Solver changed"
                                    (job/step! context 0.005 :backward-euler))))
             (is (= before (job/snapshot context)))))))))

(az/defn set-force-tolerance! :void [[context [:* job/Context]] [tolerance :f64]]
  (set! (az/field (az/field context problem) force-tolerance) tolerance))

(az/defn coefficient-displacement p/Vec3 [[context [:* job/Context]] [index :usize]]
  (az/index (az/field (az/field context problem) initial) index))

(az/defn coefficient-velocity p/Vec3 [[context [:* job/Context]] [index :usize]]
  (az/index (az/field context velocity) index))

(defn write-mode-evidence!
  "Run the independently supplied elastic eigenmode; export native coefficients.
  Use verify.py mixed --prepare-mode / --verify-mode around this function.
  Refuses to overwrite evidence. A failed run leaves an incomplete CSV."
  [input output]
  (let [{:keys [duration velocities]} (edn/read-string (slurp input))
        tolerance 1e-9
        file (io/file output)]
    (when-not (and (number? duration) (Double/isFinite (double duration)) (pos? duration))
      (throw (ex-info "Mode duration must be finite and positive" {:duration duration})))
    (io/make-parents file)
    (with-open [writer (java.nio.file.Files/newBufferedWriter
                        (.toPath file) java.nio.charset.StandardCharsets/UTF_8
                        (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW
                                                             java.nio.file.StandardOpenOption/WRITE]))]
      (.write writer "method,steps,index,displacement,velocity,force_tolerance,max_force_residual\n")
      (doseq [method [:backward-euler :sdirk2]
              steps [16 32 64]]
        (job/with-context! (assoc simplex :initial-velocities velocities)
          (fn [context]
            (set-force-tolerance! context tolerance)
            (let [advance! (if (= method :sdirk2) job/advance-sdirk! job/advance!)
                  residual (reduce
                             (fn [maximum tick]
                               (let [report (az/value (advance! context (/ duration steps)))]
                                 (when-not (zero? (:status report))
                                   (throw (ex-info "Elastic mode solve failed"
                                                  {:method method :steps steps :tick tick :report report})))
                                 (max maximum (:force-residual report))))
                             0.0 (range steps))]
              (doseq [node (range 4)
                      axis (range 3)]
                (let [displacement (az/value (coefficient-displacement context (+ 4 node)))
                      velocity (az/value (coefficient-velocity context (+ 4 node)))
                      coordinate ([:x :y :z] axis)]
                  (.write writer
                          (str (string/join "," [(name method) steps (+ (* node 3) axis)
                                                (coordinate displacement) (coordinate velocity)
                                                tolerance residual]) "\n"))))
              (.flush writer)
              (prn {:method method :steps steps :maximum-force-residual-N residual})
              (flush))))))
    {:output (.getCanonicalPath file)}))

(deftest quadratic-shared-controls-free-fall-and-rollback
  (let [description (assoc two-cell :trace-degree 2 :floor? false
                                    :initial-velocities (vec (repeat 5 [0.3 0.4 -0.2])))
        prepared (job/quadratic-controls description)
        rest (get-in prepared [:mesh :points])
        duration 0.001]
    ;; The common face shares all three edge coefficients, irrespective of
    ;; the two cells' different local edge numbers.
    (is (= 14 (count rest)))
    (is (= 3 (count (clojure.set/intersection (set (first (:cell-edges prepared)))
                                            (set (second (:cell-edges prepared)))))))
    (doseq [method [:backward-euler :sdirk2]]
      (job/with-context! description
        (fn [context]
          (let [before (job/snapshot context)]
            (set-iteration-limit! context 0)
            (is (not= 0 (:status (job/step! context duration method))))
            (is (= before (job/snapshot context)))
            (set-iteration-limit! context 100)
            (let [report (job/step! context duration method)
                  {:keys [positions observables]} (job/snapshot context)
                  factor (if (= method :sdirk2) 0.5 1.0)
                  shift [(* 0.3 duration) (- (* 0.4 duration) (* factor 9.81 duration duration)) (* -0.2 duration)]]
              (is (= 0 (:status report)) (pr-str report))
              (is (> (:path-lower-bound report) 0.9999999))
              (is (< (:elastic observables) 1e-14))
              (doseq [[point actual] (map vector rest positions)]
                (is (< (fixture/maximum (map - actual (mapv + point shift))) 1e-9)))
              (is (near-vector? [12.0 (* 40.0 (- 0.4 (* 9.81 duration))) -8.0]
                                (:momentum observables) 1e-8))))))))
  (is (thrown? clojure.lang.ExceptionInfo
               (job/with-context! (assoc simplex :trace-degree 3) (fn [_] nil)))))
