(ns field-lab.coupled-cache-job
  "Bake three interacting solids into an owned synchronized 240 Hz cache group."
  (:require [aguafria.zig :as az]
            [field-lab.physics :as p]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.coupled-job :as joint]
            [field-lab.refined-job :as refined]
            [field-lab.fem-job :as linear]
            [pitoco.geometry :as geometry]))

(defn build!
  [{:keys [refinement seconds maximum-step stiffness config geometry]
    :or {refinement 1 seconds 1.0 maximum-step 0.0001 stiffness 10000.0 config {} geometry :sphere}}]
  (when-not (and (integer? refinement) (<= 0 refinement 2)
                 (number? seconds) (Double/isFinite (double seconds)) (<= (/ 1.0 240.0) seconds 60.0)
                 (< (abs (- (* seconds 240.0) (Math/rint (* seconds 240.0)))) 1.0e-8)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step)
                 (number? stiffness) (<= 1000.0 stiffness 100000.0))
    (throw (ex-info "Use refinement 0–2, whole 240 Hz output ticks, and finite positive integration controls" {})))
  (let [settings (merge (az/value (p/defaults)) {:mass 20.0 :height 0.5 :vx 1.5 :vz 0.0 :spin 0.0} config)
        {:keys [radius mass height gravity friction vx vz spin]} settings
        _ (when-not (and (every? #(and (number? %) (Double/isFinite (double %))) (vals settings))
                          (<= 0.1 radius 1.0) (<= 0.05 mass 20.0) (<= 0.0 height 8.0)
                          (<= 0.0 gravity 20.0) (<= 0.0 friction 1.0)
                          (<= -4.0 vx 4.0) (<= -4.0 vz 4.0) (<= -20.0 spin 20.0))
            (throw (ex-info "Invalid coupled ball configuration in SI units" {})))
        descriptions (mapv (fn [side]
                              (let [center [(* side 2.3 radius) (+ height radius) 0.0]
                                    velocity [(* (- side) vx) 0.0 (* (- side) vz)]
                                    description (joint/sphere
                                                 {:refinement refinement :radius radius :center center
                                                  :velocity velocity :young stiffness
                                                  :geometry geometry
                                                  :gravity [0.0 (- gravity) 0.0] :floor? true :friction friction})]
                                (assoc description
                                       :density-kg-m3 (/ mass (get-in description [:mesh :metrics :volume]))
                                       :initial-velocities
                                       (mapv #(mapv + velocity (linear/cross [0.0 spin 0.0] (linear/subtract % center)))
                                             (get-in description [:mesh :points]))))) [-1.0 0.0 1.0])
        frames (inc (long (Math/rint (* seconds 240.0))))
        nodes (mapv #(count (get-in % [:mesh :points])) descriptions)
        _ (when (> (* frames (reduce + nodes)) 8000000)
            (throw (ex-info "Coupled cache exceeds the total 384 MB particle-storage budget" {})))
        version (joint/solver-version)]
    (joint/with-system! descriptions
      (fn [assembly states _]
        (let [owned (group/create!)
              reports (atom [])]
          (try
            (doseq [[description node-count] (map vector descriptions nodes)]
              (let [faces (refined/boundary-faces (:mesh description))
                    item (cache/create! node-count (count (get-in description [:mesh :cells]))
                                        (count faces) frames settings (double stiffness) 0.4)]
                (group/add! owned item)
                (doseq [[index [a b c]] (map-indexed vector faces)] (cache/set-face! item index a b c))))
            (doseq [[body state] (map-indexed vector states)]
              (cache/record! (group/item owned body) state 0.0))
            (dotimes [tick (dec frames)]
              (when (.isInterrupted (Thread/currentThread))
                (throw (ex-info "Coupled bake cancelled before publication" {:tick tick})))
              (swap! reports conj (joint/advance! assembly (/ 1.0 240.0) maximum-step))
              (doseq [[body state] (map-indexed vector states)]
                (cache/record! (group/item owned body) state (/ (inc tick) 240.0))))
            (when (not= version (joint/solver-version))
              (throw (ex-info "Solver changed during coupled bake; rerun with the new version" {})))
            {:group owned :frames frames :bodies 3 :nodes nodes
             :tetrahedra (mapv #(count (get-in % [:mesh :cells])) descriptions)
             :descriptions descriptions :config settings :solver-version version
             :maximum-step maximum-step :reports @reports}
            (catch Throwable error
              (group/destroy! owned)
              (throw error))))))))
