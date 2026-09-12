(ns field-lab.impact-study
  "Reproducible mesh/time studies for a frictionless hyperelastic plane impact.
  This measures numerical sensitivity; it does not certify a physical material."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [pitoco.geometry :as geometry]))

(az/defn body-height
  :- :f64
  [[state [:* dynamics/Dynamics]]]
  (let [mesh (az/field state mesh)
        ^{:var :f64} lower 1.0e30
        ^{:var :f64} upper -1.0e30]
    (dotimes [node (az/field (az/field mesh positions) len)]
      (let [height (az/field (dynamics/position state node) y)]
        (az/set-many!
          lower (ak/min lower height)
          upper (ak/max upper height))))
    (- upper lower)))

(defn solver-version
  "Fingerprint loaded implementations, including the diagnostic used here."
  []
  (into (sorted-map)
        (for [module ['field-lab.fem 'field-lab.physics 'field-lab.hyperelastic
                      'field-lab.nonlinear-fem 'field-lab.impact-study]
              :let [info (az/module-info module)]]
          [(str module)
           {:zig-version (:zig-version info)
            :declarations (->> (:definitions info)
                               (map #(select-keys % [:logical-id :implementation-fingerprint
                                                    :schema-fingerprint]))
                               (sort-by (comp pr-str :logical-id))
                               vec)}])))

(defn description
  "Select a fixed polyhedral domain or a spherical-boundary sequence.
  E, density and velocity stay fixed. Gravity and friction are disabled."
  ([refinement] (description refinement :polyhedron))
  ([refinement geometry]
   (let [mesh (if (= geometry :polyhedron)
                (nth (iterate job/refine (job/sphere-mesh 0.05 [0.0 0.051 0.0])) refinement)
                (pitoco.geometry/sphere {:radius 0.05 :center [0.0 0.051 0.0]
                                         :refinement refinement :geometry geometry}))]
     {:mesh mesh
      :material {:young-Pa 100000.0 :poisson-ratio 0.4}
      :density-kg-m3 1100.0
      :gravity [0.0 0.0 0.0]
      :floor? true
      :friction 0.0
      :initial-velocities (vec (repeat (count (:points mesh)) [0.0 -1.0 0.0]))})))

(defn measurement [state time]
  (let [values (az/value (dynamics/evaluate! state))]
    (assoc values :time-s time
           :height-m (body-height state)
           :energy-J (+ (:elastic-energy values) (:kinetic-energy values)
                        (:potential-energy values)))))

(defn summarize [samples reports]
  (let [initial (first samples)
        final (last samples)
        contact-indices (keep-indexed #(when (pos? (:normal-impulse %2)) %1) reports)
        impulse (reduce + 0.0 (map :normal-impulse reports))
        momentum-change (- (get-in final [:momentum :y]) (get-in initial [:momentum :y]))]
    {:mass-kg (:mass initial)
     :initial-energy-J (:energy-J initial)
     :final-energy-J (:energy-J final)
     :energy-loss-fraction (- 1.0 (/ (:energy-J final) (:energy-J initial)))
     :maximum-energy-gain-fraction (- (/ (apply max (map :energy-J samples))
                                        (:energy-J initial)) 1.0)
     :minimum-height-m (apply min (map :height-m samples))
     :minimum-clearance-m (apply min (map :minimum-height samples))
     :minimum-jacobian (apply min (concat (map :minimum-jacobian samples)
                                         (map :minimum-jacobian reports)))
     :peak-elastic-energy-J (apply max (map :elastic-energy samples))
     :final-center-y-m (get-in final [:center :y])
     :final-center-velocity-y-m-s (/ (get-in final [:momentum :y]) (:mass final))
     :normal-impulse-N-s impulse
     :momentum-balance-error-N-s (abs (- impulse momentum-change))
     ;; Contact times are brackets at the reporting cadence, not exact events.
     :first-contact-bracket-s (when-let [index (first contact-indices)]
                               [(:time-s (samples index)) (:time-s (samples (inc index)))])
     :last-contact-bracket-s (when-let [index (last contact-indices)]
                              [(:time-s (samples index)) (:time-s (samples (inc index)))])
     :contact-free-at-end? (boolean (and (pos? (:minimum-height final))
                                         (seq contact-indices)
                                         (zero? (:normal-impulse (last reports)))))
     :substeps (reduce + 0 (map :substeps reports))
     :rejected (reduce + 0 (map :rejected reports))}))

(defn run-case!
  [{:keys [refinement maximum-step seconds sample-dt geometry]
    :or {refinement 0 maximum-step 0.0000025 seconds 0.06 sample-dt 0.00025 geometry :polyhedron}}]
  (when-not (and (integer? refinement) (<= 0 refinement 3)
                 (every? #(and (number? %) (Double/isFinite (double %)) (pos? %))
                         [maximum-step seconds sample-dt])
                 (<= sample-dt seconds 1.0))
    (throw (ex-info "Invalid impact refinement, steps or duration" {})))
  (az/await! 'field-lab.impact-study)
  (let [version (solver-version)
        started (System/nanoTime)
        result
        (job/with-state! (description refinement geometry)
          (fn [state input]
            (let [frames (long (Math/ceil (/ seconds sample-dt)))
                  samples (atom [(measurement state 0.0)])
                  reports (atom [])]
              (doseq [frame (range 1 (inc frames))]
                (let [target (min seconds (* frame sample-dt))
                      previous (:time-s (peek @samples))]
                  (swap! reports conj (job/advance! state (- target previous) maximum-step))
                  (swap! samples conj (measurement state target))))
              {:job input
               :refinement refinement :geometry geometry
               :nodes (count (get-in input [:mesh :points]))
               :tetrahedra (count (get-in input [:mesh :cells]))
               :maximum-step-s maximum-step :sample-dt-s sample-dt :seconds seconds
               :summary (summarize @samples @reports)
               :samples @samples})))]
    (when (not= version (solver-version))
      (throw (ex-info "Solver changed during the impact study; rerun this case" {})))
    (assoc result :wall-seconds (/ (- (System/nanoTime) started) 1.0e9)
           :solver-version version)))

(defn compare-cases
  "Absolute differences, including trajectory maxima at identical output times.
  Avoid claiming an order from three nonsmooth contact solutions."
  [coarse fine]
  (when-not (= (mapv :time-s (:samples coarse)) (mapv :time-s (:samples fine)))
    (throw (ex-info "Comparison requires identical sample times" {})))
  (let [a (:summary coarse)
        b (:summary fine)]
    {:coarse [(:refinement coarse) (:maximum-step-s coarse)]
     :fine [(:refinement fine) (:maximum-step-s fine)]
     :minimum-height-difference-m (abs (- (:minimum-height-m a) (:minimum-height-m b)))
     :final-velocity-difference-m-s (abs (- (:final-center-velocity-y-m-s a)
                                          (:final-center-velocity-y-m-s b)))
     :energy-loss-fraction-difference (abs (- (:energy-loss-fraction a) (:energy-loss-fraction b)))
     :center-trajectory-max-difference-m
     (apply max (map #(abs (- (get-in %1 [:center :y]) (get-in %2 [:center :y])))
                    (:samples coarse) (:samples fine)))}))

(defn study!
  "Refine time on each mesh so spatial changes can be compared with time error.
  Write after every case to retain completed evidence if a later solve fails."
  [{:keys [refinements maximum-steps output] :as options
    :or {refinements [0 1 2] maximum-steps [0.00001 0.000005 0.0000025]
         output "exports/fem-impact-study.edn"}}]
  (when-not (and (seq refinements) (seq maximum-steps)
                 (apply < refinements) (apply > maximum-steps))
    (throw (ex-info "Use increasing refinements and decreasing maximum steps" {})))
  (let [results (atom [])
        base (merge {:seconds 0.06 :sample-dt 0.00025}
                    (select-keys options [:seconds :sample-dt :geometry]))
        write! (fn [extra]
                 (spit output (pr-str (merge {:format :field-lab/impact-study-v1
                                              :cases @results :complete? false
                                              :options (merge {:refinements refinements
                                                               :maximum-steps maximum-steps} base)} extra))))]
    (io/make-parents output)
    (write! {})
    (doseq [refinement refinements maximum-step maximum-steps]
      (let [result (run-case! (assoc base :refinement refinement :maximum-step maximum-step))]
        (swap! results conj result)
        (write! {})
        (prn (select-keys result [:refinement :tetrahedra :maximum-step-s :wall-seconds :summary]))
        (flush)))
    (let [time-comparisons (vec (mapcat (fn [refinement]
                                        (map #(apply compare-cases %)
                                             (partition 2 1 (filter (fn [r] (= refinement (:refinement r))) @results))))
                                      refinements))
          spatial (filter #(= (last maximum-steps) (:maximum-step-s %)) @results)
          mesh-comparisons (mapv #(apply compare-cases %) (partition 2 1 spatial))]
      (when-not (apply = (map :solver-version @results))
        (throw (ex-info "Cases used different solver versions; rerun the complete study" {})))
      (write! {:complete? true :time-comparisons time-comparisons :mesh-comparisons mesh-comparisons})
      {:output (.getCanonicalPath (io/file output)) :cases (count @results)
       :time-comparisons time-comparisons :mesh-comparisons mesh-comparisons})))

(defn -main [& [options]]
  (prn (study! (if options (edn/read-string options) {})))
  (shutdown-agents))
