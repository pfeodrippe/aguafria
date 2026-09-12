(ns field-lab.nonlinear-job
  "Owned finite-deformation jobs with ordinary Clojure mesh/state descriptions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [field-lab.fem :as fem]
            [field-lab.fem-job :as linear]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.soft-mesh :as sphere]
            [pitoco.geometry :as geometry]))

(defn vector-map [[x y z]]
  {:x (double x) :y (double y) :z (double z)})

(defn vector-data [value]
  (let [{:keys [x y z]} (az/value value)] [x y z]))

(defn sphere-mesh
  "A polyhedral solid sphere. Uniform refinement preserves this exact domain."
  [radius center]
  {:points (mapv #(mapv + center (linear/scale radius %)) (:points sphere/mesh))
   :cells (mapv #(into [0] %) (:triangles sphere/mesh))})

(defn refine
  "Conforming 1-to-8 tetrahedral refinement on the same polyhedral domain."
  [mesh]
  (geometry/refine mesh))

(defn with-state!
  "Construct a native job, invoke f with its handle, and release all buffers.
  A free body needs no supports; prescribed components are held fixed."
  [description f]
  (let [{:keys [mesh material constraints loads density-kg-m3 gravity floor? friction
                initial-positions initial-velocities]
         :as job} (merge {:constraints [] :loads [] :density-kg-m3 1100.0
                          :gravity [0.0 -9.81 0.0] :floor? true :friction 0.3
                          :relative-tolerance 1.0e-9 :absolute-tolerance 1.0e-10 :max-iterations 10000}
                         description)
        {:keys [points cells]} mesh
        valid-vector? #(and (= 3 (count %))
                             (every? (fn [x] (and (number? x) (Double/isFinite (double x)))) %))]
    (linear/validate! job :require-support? false)
    (when-not (and (number? density-kg-m3) (Double/isFinite (double density-kg-m3)) (pos? density-kg-m3)
                   (<= 0.0 (:poisson-ratio material))
                   (valid-vector? gravity) (boolean? floor?)
                   (number? friction) (Double/isFinite (double friction)) (<= 0.0 friction)
                   (or (nil? initial-positions) (and (= (count points) (count initial-positions))
                                                                   (every? valid-vector? initial-positions)))
                   (or (nil? initial-velocities) (and (= (count points) (count initial-velocities))
                                                                    (every? valid-vector? initial-velocities))))
      (throw (ex-info "Invalid nonlinear material, density, gravity, contact or initial state" {})))
    (when (seq loads)
      (throw (ex-info "Nonlinear jobs currently support gravity; nodal loads are not wired yet" {})))
    (az/await! 'field-lab.nonlinear-fem)
    (let [model (fem/create! (count points) (count cells) (double (:young-Pa material))
                             (double (:poisson-ratio material)))]
      (try
        (doseq [[index [x y z]] (map-indexed vector points)]
          (fem/set-node! model index (double x) (double y) (double z)))
        (doseq [[index [a b c d]] (map-indexed vector cells)]
          (when-not (fem/set-element! model index a b c d)
            (throw (ex-info "Degenerate tetrahedron" {:element index}))))
        (let [state (dynamics/create! model (double density-kg-m3) (double (:young-Pa material))
                                      (double (:poisson-ratio material)))]
          (try
            (dynamics/configure! state (vector-map gravity) floor? (double friction))
            (doseq [node (range (count points))]
              (dynamics/set-particle! state node (vector-map (get initial-positions node (points node)))
                                       (vector-map (get initial-velocities node [0.0 0.0 0.0]))))
            (doseq [[node axis value] constraints]
              (fem/constrain! model (+ (* 3 node) axis) (double value)))
            (f state job)
            (finally (dynamics/destroy! state))))
        (finally (fem/destroy! model))))))

(defn snapshot
  [state node-count]
  {:observables (az/value (dynamics/evaluate! state))
   :positions-m (mapv #(vector-data (dynamics/position state %)) (range node-count))
   :velocities-m-s (mapv #(vector-data (dynamics/particle-velocity state %)) (range node-count))})

(defn advance!
  [state seconds maximum-step]
  (when-not (and (number? seconds) (Double/isFinite (double seconds)) (<= 0.0 seconds)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step))
    (throw (ex-info "Finite nonnegative duration and positive maximum step required" {})))
  (let [report (az/value (dynamics/advance! state (double seconds) (double maximum-step)))]
    (when-not (:completed report)
      (throw (ex-info "Nonlinear integration stopped at its last accepted state" report)))
    report))

(defn solid-ball
  "Illustrative soft solid, not a calibrated hollow sports ball. SI parameters."
  ([] (solid-ball 0))
  ([refinement]
   (let [mesh (nth (iterate refine (sphere-mesh 0.05 [0.0 0.55 0.0])) refinement)]
     {:mesh mesh
      :material {:young-Pa 100000.0 :poisson-ratio 0.4}
      :density-kg-m3 1100.0
      :gravity [0.0 -9.81 0.0]
      :floor? true :friction 0.3})))

(defn bake!
  "Stream all particle states at fixed output times; internal substeps adapt
  independently of wall time. The returned report includes actual solve effort."
  [description {:keys [seconds frame-dt maximum-step output]
                :or {seconds 1.0 frame-dt (/ 1.0 240.0) maximum-step 0.0001
                     output "exports/fem-ball.edn"}}]
  (when-not (and (number? seconds) (Double/isFinite (double seconds)) (<= 0.0 seconds 60.0)
                 (number? frame-dt) (Double/isFinite (double frame-dt)) (pos? frame-dt)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step))
    (throw (ex-info "Invalid bake duration or integration steps" {})))
  (with-state! description
    (fn [state job]
      (let [frames (long (Math/ceil (/ seconds frame-dt)))
            node-count (count (get-in job [:mesh :points]))
            substeps (atom 0)
            rejected (atom 0)]
        (io/make-parents output)
        (with-open [writer (io/writer output)]
          (binding [*out* writer *print-length* nil *print-level* nil]
            (print "{:format :field-lab/hyperelastic-cache-v1 :job ")
            (pr job)
            (print " :integration ")
            (pr {:frame-dt frame-dt :maximum-step maximum-step :requested-seconds seconds})
            (println " :frames [")
            (pr (assoc (snapshot state node-count) :time 0.0))
            (println)
            (doseq [frame (range 1 (inc frames))]
              (let [target (min seconds (* frame frame-dt))
                    previous (min seconds (* (dec frame) frame-dt))
                    report (advance! state (- target previous) maximum-step)]
                (swap! substeps + (:substeps report))
                (swap! rejected + (:rejected report))
                (pr (assoc (snapshot state node-count) :time target :step-report report))
                (println)))
            (println "]}")))
        {:output (.getCanonicalPath (io/file output)) :frames (inc frames)
         :substeps @substeps :rejected @rejected}))))

(defn -main [& [input options]]
  (prn (bake! (if input (edn/read-string (slurp input)) (solid-ball))
               (if options (edn/read-string options) {})))
  (shutdown-agents))
