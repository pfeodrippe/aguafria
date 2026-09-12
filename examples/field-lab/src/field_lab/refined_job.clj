(ns field-lab.refined-job
  "Bake a refined solid ball into native owned storage for UI-thread publication."
  (:require [aguafria.zig :as az]
            [field-lab.physics :as physics]
            [field-lab.mesh-cache :as cache]
            [field-lab.nonlinear-job :as job]
            [field-lab.fem-job :as linear]
            [pitoco.geometry :as geometry]))

(defn boundary-faces [mesh]
  (geometry/boundary-faces mesh))

(defn solver-version []
  (into (sorted-map)
        (for [module ['field-lab.physics 'field-lab.fem 'field-lab.hyperelastic
                      'field-lab.nonlinear-fem 'field-lab.mesh-cache]
              :let [info (az/module-info module)]]
          [module (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
                        (sort-by (comp pr-str :logical-id) (:definitions info)))])))

(defn build!
  "Return a completed owned cache. Caller must transfer ownership or destroy it.
  Uniform 240 Hz samples keep the existing workbench timeline exact."
  [{:keys [refinement seconds maximum-step stiffness config geometry]
    :or {refinement 1 seconds 1.0 maximum-step 0.0001 stiffness 10000.0 config {} geometry :sphere}}]
  (when-not (and (integer? refinement) (<= 0 refinement 2)
                 (number? seconds) (Double/isFinite (double seconds)) (< 0.0 seconds) (<= seconds 60.0)
                 (< (abs (- (* seconds 240.0) (Math/rint (* seconds 240.0)))) 1.0e-8)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step)
                 (number? stiffness) (<= 1000.0 stiffness 100000.0))
    (throw (ex-info "Use refinement 0–2, 240 Hz duration, and finite positive integration controls" {})))
  (az/await! 'field-lab.mesh-cache)
  (let [settings (merge (az/value (physics/defaults)) {:mass 20.0 :height 0.5 :vx 0.0 :vz 0.0 :spin 0.0} config)
        {:keys [radius mass height gravity friction vx vz spin]} settings
        _ (when-not (and (every? #(and (number? %) (Double/isFinite (double %))) (vals settings))
                          (<= 0.1 radius 1.0) (<= 0.05 mass 20.0) (<= 0.0 height 8.0)
                          (<= 0.0 gravity 20.0) (<= 0.0 friction 1.0))
            (throw (ex-info "Invalid refined ball configuration in SI units" {})))
        center [-1.5 (+ height radius) -0.3]
        mesh (pitoco.geometry/sphere {:radius radius :center center :refinement refinement :geometry geometry})
        faces (boundary-faces mesh)
        frames (inc (long (Math/rint (* seconds 240.0))))
        _ (when (> (* frames (count (:points mesh))) 8000000)
            (throw (ex-info "Refined cache exceeds the 384 MB particle-storage budget" {})))
        description {:mesh mesh :material {:young-Pa stiffness :poisson-ratio 0.4}
                     :density-kg-m3 (/ mass (get-in mesh [:metrics :volume]))
                     :gravity [0.0 (- gravity) 0.0] :floor? true :friction friction
                     :initial-velocities (mapv #(mapv + [vx 0.0 vz]
                                                      (linear/cross [0.0 spin 0.0] (linear/subtract % center)))
                                              (:points mesh))}
        version (solver-version)]
    (job/with-state! description
      (fn [state _]
        (let [owned (cache/create! (count (:points mesh)) (count (:cells mesh)) (count faces)
                                    frames settings (double stiffness) 0.4)]
          (try
            (doseq [[index [a b c]] (map-indexed vector faces)]
              (cache/set-face! owned index a b c))
            (cache/record! owned state 0.0)
            (dotimes [index (dec frames)]
              (when (.isInterrupted (Thread/currentThread))
                (throw (ex-info "Refined bake cancelled before publication" {})))
              (job/advance! state (/ 1.0 240.0) maximum-step)
              (cache/record! owned state (/ (inc index) 240.0)))
            (when (not= version (solver-version))
              (throw (ex-info "Solver changed during refined bake; rerun with the new version" {})))
            {:cache owned :frames frames :nodes (count (:points mesh))
             :tetrahedra (count (:cells mesh)) :faces (count faces)
             :job description :config settings :solver-version version
             :maximum-step maximum-step}
            (catch Throwable error
              (cache/destroy! owned)
              (throw error))))))))
