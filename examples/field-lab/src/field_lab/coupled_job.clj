(ns field-lab.coupled-job
  "Clojure-authored scopes for multiple interacting finite-deformation solids."
  (:require [aguafria.zig :as az]
            [field-lab.coupled-fem :as coupled]
            [field-lab.contact-mesh :as contact]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.refined-job :as refined]
            [pitoco.geometry :as geometry]))

(defn with-system!
  "Own all meshes, states and contact surfaces for f. Nothing escapes this scope.
  Each description is a nonlinear-job map. Attached bodies must be unconstrained."
  [descriptions f]
  (when-not (and (vector? descriptions) (<= 1 (count descriptions) 3))
    (throw (ex-info "A coupled job requires one to three body descriptions" {})))
  (doseq [description descriptions]
    (when (seq (:constraints description))
      (throw (ex-info "Coupled contact currently requires freely moving bodies" {}))))
  (let [assembly (coupled/create! (count descriptions))]
    (try
      (letfn [(attach [index states]
                (if (= index (count descriptions))
                  (let [error (az/value (coupled/residual assembly))]
                    (when (> (:penetration error) 1.0e-9)
                      (throw (ex-info "Coupled bodies initially overlap at sampled boundary vertices" error)))
                    (f assembly states descriptions))
                  (job/with-state! (descriptions index)
                    (fn [state description]
                      (let [points (get description :initial-positions (get-in description [:mesh :points]))
                            faces (refined/boundary-faces (:mesh description))
                            surface (contact/build! points faces)]
                        (try
                          (coupled/attach! assembly index state surface)
                          (attach (inc index) (conj states state))
                          (finally (contact/destroy! surface))))))))]
        (attach 0 []))
      (finally (coupled/destroy! assembly)))))

(defn advance!
  [assembly seconds maximum-step]
  (when-not (and (number? seconds) (Double/isFinite (double seconds)) (<= 0.0 seconds)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step))
    (throw (ex-info "Finite nonnegative duration and positive maximum step required" {})))
  (let [report (az/value (coupled/advance! assembly (double seconds) (double maximum-step)))]
    (when-not (:completed report)
      (throw (ex-info "Coupled integration stopped at its last accepted all-body state" report)))
    report))

(defn sphere
  "An illustrative solid for contact studies, in SI units. No material calibration implied."
  [{:keys [refinement radius center velocity young density gravity floor? friction geometry]
    :or {refinement 1 radius 0.05 center [0.0 0.0 0.0] velocity [0.0 0.0 0.0]
         geometry :sphere young 10000.0 density 1100.0 gravity [0.0 0.0 0.0] floor? false friction 0.0}}]
  (when-not (and (integer? refinement) (<= 0 refinement 2)
                 (number? radius) (Double/isFinite (double radius)) (pos? radius))
    (throw (ex-info "Use sphere refinement 0–2 and a finite positive radius" {})))
  (let [mesh (pitoco.geometry/sphere {:radius radius :center center :refinement refinement :geometry geometry})]
    {:mesh mesh :material {:young-Pa young :poisson-ratio 0.4}
     :density-kg-m3 density :gravity gravity :floor? floor? :friction friction
     :initial-velocities (vec (repeat (count (:points mesh)) velocity))}))

(defn solver-version []
  (merge (refined/solver-version)
         (into (sorted-map)
               (for [module ['field-lab.contact-mesh 'field-lab.coupled-fem]
                     :let [info (az/module-info module)]]
                 [module (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
                               (sort-by (comp pr-str :logical-id) (:definitions info)))]))))

(defn head-on-study!
  "Reproducible isolated two/three-solid impacts with momentum and contact residuals.
  This measures the discrete contact solver, not experimental material accuracy."
  [{:keys [bodies refinement seconds maximum-step friction geometry]
    :or {bodies 3 refinement 1 seconds 0.06 maximum-step 0.00005 friction 0.0 geometry :polyhedron}}]
  (when-not (and (#{2 3} bodies) (number? seconds)
                 (Double/isFinite (double seconds)) (<= 0.001 seconds 1.0)
                 (< (abs (- (* seconds 1000.0) (Math/rint (* seconds 1000.0)))) 1.0e-8))
    (throw (ex-info "Study requires two or three bodies and whole millisecond output times" {})))
  (let [sources (if (= bodies 2)
                  [[[-0.0525 0.0 0.0] [1.0 0.0 0.0]] [[0.0525 0.0 0.0] [-1.0 0.0 0.0]]]
                  [[[-0.105 0.0 0.0] [1.0 0.0 0.0]] [[0.0 0.0 0.0] [0.0 0.0 0.0]]
                   [[0.105 0.0 0.0] [-1.0 0.0 0.0]]])
        descriptions (mapv (fn [[center velocity]]
                              (sphere {:refinement refinement :center center :velocity velocity :friction friction :geometry geometry}))
                            sources)
        version (solver-version)
        started (System/nanoTime)]
    (with-system! descriptions
      (fn [assembly states _]
        (let [observe (fn [time]
                        {:time time :total (az/value (coupled/observe! assembly))
                         :bodies (mapv #(az/value (dynamics/evaluate! %)) states)
                         :x-gaps (mapv #(coupled/x-gap assembly % (inc %)) (range (dec bodies)))
                         :contact (az/value (coupled/residual assembly))})
              initial (observe 0.0)
              reports (atom [])
              history (into [initial]
                            (for [tick (range 1 (inc (long (Math/rint (* seconds 1000.0)))))]
                              (do
                                (when (.isInterrupted (Thread/currentThread))
                                  (throw (ex-info "Coupled study cancelled" {:tick tick})))
                                (swap! reports conj (advance! assembly 0.001 maximum-step))
                                (observe (/ tick 1000.0)))))]
          (when (not= version (solver-version))
            (throw (ex-info "Solver changed during coupled study; rerun with the new version" {})))
          {:descriptions descriptions :solver-version version :maximum-step maximum-step
           :history history :reports @reports :elapsed-seconds (/ (- (System/nanoTime) started) 1.0e9)})))))
