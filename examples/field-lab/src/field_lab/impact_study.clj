(ns field-lab.impact-study
  "Reproducible mesh/time studies for a frictionless hyperelastic plane impact.
  This measures numerical sensitivity; it does not certify a physical material."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.coupled-job :as joint]
            [field-lab.variational :as implicit]
            [field-lab.mixed-job :as mixed-job]
            [pitoco.geometry :as geometry]))

(az/defstruct InertiaTensor {:layout :extern}
  [[:diagonal p/Vec3] [:off-diagonal p/Vec3]])

(az/defstruct InertiaAudit {:layout :extern}
  [[:integrated-mass :f64] [:nodal-mass :f64]
   [:center p/Vec3] [:nodal-center-offset p/Vec3]
   [:integrated-inertia InertiaTensor] [:nodal-inertia InertiaTensor]
   [:integrated-kinetic-energy :f64] [:nodal-kinetic-energy :f64]
   [:integrated-momentum p/Vec3] [:nodal-momentum p/Vec3]
   [:integrated-angular-momentum p/Vec3] [:nodal-angular-momentum p/Vec3]])

(az/defn add-inertia! :void
  "Add weight * (|point|^2 I - point point^T); off-diagonal order is xy,xz,yz."
  [[tensor [:* InertiaTensor]] [point p/Vec3] [weight :f64]]
  (let [x (az/field point x)
        y (az/field point y)
        z (az/field point z)]
    (az/set-many!
      (az/field tensor diagonal)
      (p/add (az/field tensor diagonal)
             (p/scale (p/v (+ (* y y) (* z z)) (+ (* x x) (* z z)) (+ (* x x) (* y y))) weight))
      (az/field tensor off-diagonal)
      (p/add (az/field tensor off-diagonal) (p/scale (p/v (- (* x y)) (- (* x z)) (- (* y z))) weight)))))

(az/defn inertia-audit InertiaAudit
  "Integrate the current P1 position/velocity fields over reference tetrahedra.
  Compare with the actual nodal masses without changing the state. Density is
  reference density; current volume must not create or remove material mass.
  Both tensors/angular momenta use the integrated center as their origin."
  [[state [:* dynamics/Dynamics]] [density :f64]]
  (let [mesh (az/field state mesh)
        elements (az/field mesh elements)
        origin (dynamics/position state 0)
        ^:var first-moment (ak/as (p/v 0.0 0.0 0.0) p/Vec3)
        ^:var result (ak/as (mem/zeroes (az/type InertiaAudit)) InertiaAudit)]
    ;; Accumulate relative to a local origin to avoid subtracting two large
    ;; world-coordinate second moments when the body is far from the origin.
    (dotimes [index (az/field elements len)]
      (let [element (az/index elements index)
            mass (* density (az/field element volume))]
        (ak/+= (az/field result integrated-mass) mass)
        (dotimes [local 4]
          (let [point (dynamics/position state (az/index (az/field element nodes) local))]
            (set! first-moment (p/add first-moment (p/scale (p/add point (p/scale origin -1.0)) (* 0.25 mass))))))))
    (let [center-offset (p/scale first-moment (/ 1.0 (az/field result integrated-mass)))]
      (set! (az/field result center) (p/add origin center-offset))
      (dotimes [node (az/field (az/field state masses) len)]
        (let [mass (az/index (az/field state masses) node)
              relative (p/add (p/add (dynamics/position state node) (p/scale origin -1.0))
                              (p/scale center-offset -1.0))
              velocity (az/index (az/field state velocities) node)]
          (ak/+= (az/field result nodal-mass) mass)
          (add-inertia! (ak/& (az/field result nodal-inertia)) relative mass)
          (az/set-many!
            (az/field result nodal-center-offset)
            (p/add (az/field result nodal-center-offset) (p/scale relative mass))
            (az/field result nodal-momentum)
            (p/add (az/field result nodal-momentum) (p/scale velocity mass))
            (az/field result nodal-angular-momentum)
            (p/add (az/field result nodal-angular-momentum) (p/scale (p/cross relative velocity) mass)))
          (ak/+= (az/field result nodal-kinetic-energy) (* 0.5 mass (p/dot velocity velocity)))))
      (set! (az/field result nodal-center-offset)
            (p/scale (az/field result nodal-center-offset) (/ 1.0 (az/field result nodal-mass))))
      ;; Exact P1 products: integral Ni*Nj = V*(1+delta_ij)/20.
      (dotimes [index (az/field elements len)]
        (let [element (az/index elements index)
              mass (* density (az/field element volume))
              ^:var sum-position (ak/as (p/v 0.0 0.0 0.0) p/Vec3)
              ^:var sum-velocity (ak/as (p/v 0.0 0.0 0.0) p/Vec3)
              ^:var sum-speed-squared (ak/f64 0.0)
              ^:var sum-cross (ak/as (p/v 0.0 0.0 0.0) p/Vec3)]
          (dotimes [local 4]
            (let [node (az/index (az/field element nodes) local)
                  relative (p/add (p/add (dynamics/position state node) (p/scale origin -1.0))
                                  (p/scale center-offset -1.0))
                  velocity (az/index (az/field state velocities) node)]
              (add-inertia! (ak/& (az/field result integrated-inertia)) relative (* 0.05 mass))
              (az/set-many!
                sum-position (p/add sum-position relative)
                sum-velocity (p/add sum-velocity velocity)
                sum-cross (p/add sum-cross (p/cross relative velocity)))
              (ak/+= sum-speed-squared (p/dot velocity velocity))))
          (add-inertia! (ak/& (az/field result integrated-inertia)) sum-position (* 0.05 mass))
          (ak/+= (az/field result integrated-kinetic-energy)
                 (* 0.025 mass (+ sum-speed-squared (p/dot sum-velocity sum-velocity))))
          (az/set-many!
            (az/field result integrated-momentum)
            (p/add (az/field result integrated-momentum) (p/scale sum-velocity (* 0.25 mass)))
            (az/field result integrated-angular-momentum)
            (p/add (az/field result integrated-angular-momentum)
                   (p/scale (p/add sum-cross (p/cross sum-position sum-velocity)) (* 0.05 mass)))))))
    result))

(az/defn body-height :f64
  [[state [:* dynamics/Dynamics]]]
  (let [mesh (az/field state mesh)
        ^:var lower (ak/f64 1.0e30)
        ^:var upper (ak/f64 -1.0e30)]
    (dotimes [node (az/field (az/field mesh positions) len)]
      (let [height (az/field (dynamics/position state node) y)]
        (az/set-many!
          lower (ak/min lower height)
          upper (ak/max upper height))))
    (- upper lower)))

(az/defstruct RodFields {:layout :extern}
  [[:maximum-section-velocity-spread :f64]
   [:maximum-section-height-spread :f64]
   [:maximum-transverse-speed :f64]
   [:transverse-kinetic-energy :f64]])

(az/defn rod-fields RodFields
  "Inspect Cartesian material sections of the benchmark's box-mesh ordering.
  A one-dimensional axial wave has zero section spread and transverse velocity."
  [[state [:* dynamics/Dynamics]] [nx :usize] [ny :usize] [nz :usize]]
  (let [^:var result
        (ak/as (RodFields {:maximum-section-velocity-spread 0.0
                    :maximum-section-height-spread 0.0
                    :maximum-transverse-speed 0.0
                    :transverse-kinetic-energy 0.0}) RodFields)]
    (dotimes [j (+ ny 1)]
      (let [^:var minimum-velocity (ak/f64 1.0e30)
            ^:var maximum-velocity (ak/f64 -1.0e30)
            ^:var minimum-height (ak/f64 1.0e30)
            ^:var maximum-height (ak/f64 -1.0e30)]
        (dotimes [k (+ nz 1)]
          (dotimes [i (+ nx 1)]
            (let [node (+ i (* (+ nx 1) (+ j (* (+ ny 1) k))))
                  velocity (az/index (az/field state velocities) node)
                  height (az/field (dynamics/position state node) y)
                  transverse-squared (+ (* (az/field velocity x) (az/field velocity x))
                                        (* (az/field velocity z) (az/field velocity z)))
                  mass (az/index (az/field state masses) node)]
              (az/set-many!
                minimum-velocity (ak/min minimum-velocity (az/field velocity y))
                maximum-velocity (ak/max maximum-velocity (az/field velocity y))
                minimum-height (ak/min minimum-height height)
                maximum-height (ak/max maximum-height height)
                (az/field result maximum-transverse-speed)
                (ak/max (az/field result maximum-transverse-speed) (ak/sqrt transverse-squared))
                (az/field result transverse-kinetic-energy)
                (+ (az/field result transverse-kinetic-energy) (* 0.5 mass transverse-squared))))))
        (az/set-many!
          (az/field result maximum-section-velocity-spread)
          (ak/max (az/field result maximum-section-velocity-spread) (- maximum-velocity minimum-velocity))
          (az/field result maximum-section-height-spread)
          (ak/max (az/field result maximum-section-height-spread) (- maximum-height minimum-height)))))
    result))

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

(defn inertia-comparison
  "Compare both mass discretizations about the same integrated center.
  Tensor Frobenius norms count symmetric off-diagonal entries twice."
  [audit]
  (let [components (fn [tensor]
                     (concat (map (:diagonal tensor) [:x :y :z])
                             (map #(* (Math/sqrt 2.0) %) (map (:off-diagonal tensor) [:x :y :z]))))
        exact (components (:integrated-inertia audit))
        nodal (components (:nodal-inertia audit))
        norm (fn [values] (Math/sqrt (reduce + (map #(* % %) values))))]
    {:mass-relative-error (/ (- (:nodal-mass audit) (:integrated-mass audit)) (:integrated-mass audit))
     :center-offset-m (norm (vals (:nodal-center-offset audit)))
     :inertia-relative-frobenius-error (/ (norm (map - nodal exact)) (norm exact))
     :kinetic-energy-difference-J (- (:nodal-kinetic-energy audit) (:integrated-kinetic-energy audit))
     :angular-momentum-difference-kg-m2-s
     (mapv - (map (:nodal-angular-momentum audit) [:x :y :z])
             (map (:integrated-angular-momentum audit) [:x :y :z]))}))

(defn audit-inertia!
  "Inspect an authored body's mass discretization using native tetrahedral integration.
  Does not advance time. Includes source geometry and native implementation identity."
  [description]
  (az/await! 'field-lab.impact-study)
  (let [version (solver-version)
        result (job/with-state! description
                 (fn [state input]
                   (let [audit (az/value (inertia-audit state (double (:density-kg-m3 input))))]
                     {:description input :audit audit :comparison (inertia-comparison audit)})))]
    (when-not (= version (solver-version))
      (throw (ex-info "Native implementation changed during the inertia audit" {})))
    (assoc result :solver-version version)))

(defn surface-massless-radius-bound
  "Necessary condition for positive scalar masses with every surface node massless.
  Preserving mass, center and inertia requires trace(I)/(2M) <= max interior r^2.
  Passing this bound does not prove a redistribution exists. Normal-only or mixed
  matrix formulations are different candidates and are not rejected by this test."
  [{:keys [description audit]}]
  (let [mesh (:mesh description)
        points (or (:initial-positions description) (:points mesh))
        boundary (set (mapcat identity (geometry/boundary-faces mesh)))
        interior (remove boundary (range (count points)))
        center (mapv (:center audit) [:x :y :z])
        required-radius-squared (/ (reduce + (vals (get-in audit [:integrated-inertia :diagonal])))
                                    (* 2.0 (:integrated-mass audit)))
        maximum-radius-squared (reduce max 0.0
                                      (map (fn [node]
                                             (let [relative (geometry/subtract (points node) center)]
                                               (geometry/dot relative relative))) interior))
        roundoff (* 1e-12 (max required-radius-squared maximum-radius-squared))]
    {:interior-nodes (count interior)
     :surface-nodes (count boundary)
     :required-mean-squared-radius-m2 required-radius-squared
     :maximum-interior-squared-radius-m2 maximum-radius-squared
     :necessary-radius-bound-satisfied?
     (boolean (and (seq interior) (<= required-radius-squared (+ maximum-radius-squared roundoff))))}))

(defn rod-impact-reference
  "Small-strain, zero-Poisson-ratio bar impacting a frictionless rigid plane.
  The characteristic solution gives c=sqrt(E/rho), force=rho*c*v*A and
  contact duration 2L/c. These predictions do not use the numerical solver."
  [{:keys [length-m width-m young-Pa density-kg-m3 speed-m-s gap-m]}]
  (when-not (every? #(and (number? %) (Double/isFinite (double %)) (pos? %))
                   [length-m width-m young-Pa density-kg-m3 speed-m-s gap-m])
    (throw (ex-info "Rod dimensions, material, speed and gap must be finite and positive" {})))
  (let [wave-speed (Math/sqrt (/ young-Pa density-kg-m3))
        mass (* density-kg-m3 length-m width-m width-m)
        impact-time (/ gap-m speed-m-s)
        contact-time (/ (* 2.0 length-m) wave-speed)]
    {:wave-speed-m-s wave-speed
     :strain-scale (/ speed-m-s wave-speed)
     :mass-kg mass
     :speed-m-s speed-m-s
     :impact-time-s impact-time
     :contact-duration-s contact-time
     :release-time-s (+ impact-time contact-time)
     :contact-force-N (* density-kg-m3 wave-speed speed-m-s width-m width-m)
     :total-contact-impulse-N-s (* 2.0 mass speed-m-s)
     :initial-energy-J (* 0.5 mass speed-m-s speed-m-s)}))

(defn rod-reference-velocity [reference time]
  (let [fraction (max 0.0 (min 1.0 (/ (- time (:impact-time-s reference))
                                      (:contact-duration-s reference))))]
    (* (:speed-m-s reference) (- (* 2.0 fraction) 1.0))))

(defn rod-reference-average-force [reference begin end]
  (when-not (< begin end)
    (throw (ex-info "Force averaging needs an increasing time interval" {})))
  (* (:contact-force-N reference)
     (/ (max 0.0 (- (min end (:release-time-s reference))
                    (max begin (:impact-time-s reference))))
        (- end begin))))

(defn run-mixed-rod-impact!
  "Run the experimental mixed tetrahedra against the same analytical rod pulse.
  Every average force uses an accepted integrator impulse. On failure, retain the
  accepted prefix and failed report; never label a partial run complete."
  [options]
  (let [defaults {:divisions [1 2 1] :length-m 0.1 :width-m 0.02
                  :young-Pa 1.0e6 :density-kg-m3 1000.0 :speed-m-s 0.01
                  :gap-m 2e-6 :steps-per-contact 32 :integration :backward-euler :trace-degree 1 :cache-positions? false}
        unknown (remove (conj (set (keys defaults)) :on-sample) (keys options))
        {:keys [divisions length-m width-m young-Pa density-kg-m3 speed-m-s
                steps-per-contact integration trace-degree cache-positions? on-sample] :as settings} (merge defaults options)]
    (when (or (seq unknown)
              (not (and (vector? divisions) (= 3 (count divisions))
                        (every? #(and (integer? %) (<= 1 % 128)) divisions)
                        (integer? steps-per-contact) (<= 16 steps-per-contact 100000)
                        (#{:backward-euler :sdirk2} integration)
                        (#{1 2} trace-degree) (boolean? cache-positions?)
                        (or (nil? on-sample) (ifn? on-sample)))))
      (throw (ex-info "Invalid mixed rod settings" {:unknown (vec unknown)})))
    (let [reference (rod-impact-reference settings)
          _ (when (> (:strain-scale reference) 0.001)
              (throw (ex-info "The linear rod reference requires speed/wave-speed <= 0.001" {})))
          mesh (update (geometry/box-mesh divisions [width-m length-m width-m]) :points
                       #(mapv (fn [[x y z]] [(- x (* 0.5 width-m)) (+ y (:gap-m settings))
                                            (- z (* 0.5 width-m))]) %))
          description {:mesh mesh :trace-degree trace-degree :material {:young-Pa young-Pa :poisson-ratio 0.0}
                       :density-kg-m3 density-kg-m3 :gravity [0.0 0.0 0.0] :floor? true
                       :initial-velocities (vec (repeat (count (:points mesh)) [0.0 (- speed-m-s) 0.0]))}
          version (fn [] (cond-> (mixed-job/solver-version)
                           joint/*kernel-provenance*
                           (assoc 'field-lab.coupled-job/kernel joint/*kernel-provenance*)))
          before (version)
          duration (+ (:impact-time-s reference) (* 1.5 (:contact-duration-s reference)))
          dt (/ (:contact-duration-s reference) steps-per-contact)
          started (System/nanoTime)
          result
          (mixed-job/with-context! description
            (fn [context]
              (let [initial (az/value (mixed-job/observables context))]
                (assoc
                  (loop [time 0.0 tick 1 impulse 0.0 previous initial samples []]
                    (let [target (min duration (* tick dt))
                          h (- target time)
                          report (mixed-job/step! context h integration)]
                      (if (not= 0 (:status report))
                        {:status :failed :failed-time-s target :failed-report report :samples samples}
                        (let [{:keys [observables positions]} (mixed-job/snapshot context)
                              step-impulse (get-in observables [:contact-impulse :y])
                              force (/ step-impulse h)
                              total-impulse (+ impulse step-impulse)
                              momentum (get-in observables [:momentum :y])
                              sample {:begin-s time :time-s target :observation observables :report report
                                      :minimum-height-m (apply min (map second positions))
                                      :center-velocity-m-s (/ momentum (:mass observables))
                                      :reference-velocity-m-s (rod-reference-velocity reference target)
                                      :reference-average-contact-force-N (rod-reference-average-force reference time target)
                                      :average-contact-force-N force :contact-impulse-N-s total-impulse
                                      :endpoint-contact-force-N (get-in observables [:contact-force :y])
                                      :step-momentum-error-N-s (- momentum (get-in previous [:momentum :y]) step-impulse)}
                              sample (cond-> sample cache-positions? (assoc :trace-controls positions))
                              samples (conj samples sample)]
                          (when on-sample (on-sample sample))
                          (if (>= target duration)
                            {:status :complete :samples samples}
                            (recur target (inc tick) total-impulse observables samples))))))
                  :initial initial))))]
      (when-not (= before (version))
        (throw (ex-info "Mixed solver changed during the rod study" {})))
      (assoc result :settings (dissoc settings :on-sample) :reference reference :description description
             :solver-version before :maximum-step-s dt :integration integration
             :wall-seconds (/ (- (System/nanoTime) started) 1e9)))))

(defn summarize-mixed-rod-impact
  "Keep failed status explicit. Errors on partial histories are not qualification."
  [{:keys [status initial samples reference]}]
  (when (seq samples)
    (let [last-sample (peek samples)
          observation (:observation last-sample)
          reference-impulse (:total-contact-impulse-N-s reference)]
      {:status status :accepted-steps (count samples) :end-time-s (:time-s last-sample)
       :mass-relative-error (- (/ (:mass initial) (:mass-kg reference)) 1.0)
       :final-rebound-speed-ratio (/ (:center-velocity-m-s last-sample) (:speed-m-s reference))
       :contact-impulse-relative-error (- (/ (:contact-impulse-N-s last-sample) reference-impulse) 1.0)
       :force-pulse-relative-L1-error
       (/ (reduce + (map #(* (- (:time-s %) (:begin-s %))
                            (abs (- (:average-contact-force-N %) (:reference-average-contact-force-N %)))) samples))
          reference-impulse)
       :peak-force-ratio (/ (apply max (map :average-contact-force-N samples)) (:contact-force-N reference))
       :mechanical-energy-loss-fraction
       (- 1.0 (/ (+ (:kinetic observation) (:elastic observation) (:potential observation)) (:initial-energy-J reference)))
       :maximum-step-momentum-error-N-s (apply max (map #(abs (:step-momentum-error-N-s %)) samples))
       :minimum-height-m (apply min (map :minimum-height-m samples))
       :minimum-certified-path-jacobian (apply min (map #(get-in % [:report :path-lower-bound]) samples))})))

(defn- validate-mixed-force-history! [{:keys [status samples reference]}]
  (when-not (and (= :complete status) (vector? samples) (seq samples)
                 (number? (:total-contact-impulse-N-s reference))
                 (Double/isFinite (double (:total-contact-impulse-N-s reference)))
                 (pos? (:total-contact-impulse-N-s reference)))
    (throw (ex-info "Force comparison requires complete mixed histories" {})))
  (reduce
    (fn [[previous-time previous-impulse] sample]
      (let [{:keys [begin-s time-s average-contact-force-N contact-impulse-N-s]} sample]
        (when-not (and (every? #(and (number? %) (Double/isFinite (double %)))
                              [begin-s time-s average-contact-force-N contact-impulse-N-s])
                       (= begin-s previous-time) (< begin-s time-s)
                       (< (abs (- contact-impulse-N-s previous-impulse
                                  (* (- time-s begin-s) average-contact-force-N)))
                          (* 1e-10 (:total-contact-impulse-N-s reference))))
          (throw (ex-info "Mixed force history has a gap or inconsistent impulse" {:sample sample})))
        [time-s contact-impulse-N-s]))
    [0.0 0.0] samples))

(defn compare-mixed-time-refinement
  "Compare nested accepted histories over identical force observation windows.
  Sum fine-step impulses; never interpolate force or compare differently binned
  L1 errors as if they used the same observation. Retain each raw step error so
  averaging cannot hide force ringing. This reports sensitivity, not a pass gate."
  [coarse fine]
  (doseq [run [coarse fine]] (validate-mixed-force-history! run))
  (let [coarse-steps (get-in coarse [:settings :steps-per-contact])
        fine-steps (get-in fine [:settings :steps-per-contact])
        version #(dissoc (:solver-version %) 'field-lab.coupled-job/kernel)
        reference (:reference coarse)
        normalization (:total-contact-impulse-N-s reference)
        end-time (:time-s (peek (:samples coarse)))
        tolerance (* 1e-12 end-time)
        near? #(< (abs (- %1 %2)) tolerance)]
    (when-not (and (every? #(and (map? %) (seq %))
                          (mapcat #(map % [:solver-version :description :reference :initial]) [coarse fine]))
                   (integer? coarse-steps) (pos? coarse-steps)
                   (integer? fine-steps) (> fine-steps coarse-steps)
                   (zero? (mod fine-steps coarse-steps))
                   (= (dissoc (:settings coarse) :steps-per-contact)
                      (dissoc (:settings fine) :steps-per-contact))
                   (= (version coarse) (version fine))
                   (= (:description coarse) (:description fine))
                   (= reference (:reference fine))
                   (= (:initial coarse) (:initial fine))
                   (near? end-time (:time-s (peek (:samples fine)))))
      (throw (ex-info "Mixed refinement needs matching physics and nested time steps" {})))
    (let [rows
          (loop [bins (:samples coarse) remaining (:samples fine) result []]
            (if-let [{:keys [begin-s time-s average-contact-force-N]} (first bins)]
              (let [[group tail] (split-with #(<= (:time-s %) (+ time-s tolerance)) remaining)]
                (when-not (and (seq group) (near? begin-s (:begin-s (first group)))
                               (near? time-s (:time-s (last group))))
                  (throw (ex-info "Mixed refinement sample boundaries are not nested" {:time-s time-s})))
                (let [impulse (reduce + (map #(* (- (:time-s %) (:begin-s %))
                                                 (:average-contact-force-N %)) group))]
                  (recur (next bins) tail
                         (conj result {:begin-s begin-s :time-s time-s
                                       :coarse-force-N average-contact-force-N
                                       :fine-force-N (/ impulse (- time-s begin-s))
                                       :reference-force-N (rod-reference-average-force reference begin-s time-s)}))))
              (do
                (when (seq remaining) (throw (ex-info "Mixed refinement has unconsumed samples" {})))
                result)))
          integral (fn [difference]
                     (/ (reduce + (map #(* (- (:time-s %) (:begin-s %)) (abs (difference %))) rows))
                        normalization))
          raw-error (fn [run]
                      (/ (reduce + (map #(* (- (:time-s %) (:begin-s %))
                                           (abs (- (:average-contact-force-N %)
                                                   (rod-reference-average-force reference (:begin-s %) (:time-s %)))))
                                        (:samples run))) normalization))
          coarse-error (integral #(- (:coarse-force-N %) (:reference-force-N %)))
          fine-error (integral #(- (:fine-force-N %) (:reference-force-N %)))
          fine-step-error (raw-error fine)]
      {:coarse-steps-per-contact coarse-steps :fine-steps-per-contact fine-steps
       :force-observation-window-s (apply max (map #(- (:time-s %) (:begin-s %)) rows))
       :force-history-relative-L1-difference (integral #(- (:coarse-force-N %) (:fine-force-N %)))
       :coarse-common-window-force-error coarse-error :fine-common-window-force-error fine-error
       :coarse-accepted-step-force-error (raw-error coarse) :fine-accepted-step-force-error fine-step-error
       :fine-error-hidden-by-averaging (- fine-step-error fine-error)
       :fine-impulse-rebinning-error-N-s
       (- (reduce + (map #(* (- (:time-s %) (:begin-s %)) (:fine-force-N %)) rows))
          (:contact-impulse-N-s (peek (:samples fine))))
       :trace rows})))

(defn advance-with-force-audit!
  "Observe accepted steps through the existing bounded native batch interface.
  Rejected trials never contribute a force sample or impulse."
  [workspace seconds maximum-step clearance pressure options begin]
  (let [previous (atom {:substeps 0 :time begin :ground-impulse 0.0})
        records (atom [])]
    (try
      (let [report
            (implicit/advance!
             workspace seconds maximum-step clearance pressure
             (assoc options :maximum-attempts 1
                    :on-progress
                    (fn [report]
                      (when (> (:substeps report) (:substeps @previous))
                        (when-not (and (= (:substeps report) (inc (:substeps @previous)))
                                       (> (:time report) (:time @previous)))
                          (throw (ex-info "Force audit did not observe exactly one accepted step" {})))
                        (swap! records conj
                               {:begin-s (:time @previous) :end-s (:time report)
                                :impulse-N-s (- (:ground-impulse report) (:ground-impulse @previous))
                                :endpoint-force-N (:y (az/value (implicit/net-contact-force workspace)))})
                        (reset! previous report)))))]
        {:report report :accepted-force-steps @records})
      (catch Throwable error
        (throw (ex-info (ex-message error)
                        (assoc (ex-data error) :accepted-force-steps @records) error))))))

(defn run-rod-impact!
  "Measure a three-dimensional tet bar against the linear characteristic solution.
  Zero Poisson ratio and small strain suppress lateral/finite-strain corrections.
  Contact regularization and integration error remain measured numerical errors.
  Call within with-compiled-kernel! to freeze the IPC solver for a study."
  [options]
  (let [allowed #{:divisions :length-m :width-m :young-Pa :density-kg-m3 :speed-m-s :gap-m
                  :clearance-m :pressure-Pa :samples-per-contact :steps-per-contact
                  :velocity-tolerance :integration :mass-model :on-sample :audit-forces?}
        unknown (remove allowed (keys options))]
    (when (seq unknown)
      (throw (ex-info "Unknown rod benchmark settings" {:keys (vec unknown)}))))
  (let [{:keys [divisions length-m width-m young-Pa density-kg-m3 speed-m-s
                clearance-m pressure-Pa samples-per-contact steps-per-contact
                velocity-tolerance integration mass-model on-sample audit-forces?] :as settings}
        (merge {:divisions [1 8 1] :length-m 0.1 :width-m 0.02
                :young-Pa 1.0e6 :density-kg-m3 1000.0 :speed-m-s 0.01
                :clearance-m 1.0e-7 :pressure-Pa 1000.0
                :samples-per-contact 128 :steps-per-contact 256
                :velocity-tolerance 1.0e-9 :integration :backward-euler :mass-model :lumped :audit-forces? false} options)
        _ (when-not (and (vector? divisions) (= 3 (count divisions))
                         (every? #(and (integer? %) (pos? %)) divisions)
                         (every? #(and (number? %) (Double/isFinite (double %)) (pos? %))
                                [clearance-m pressure-Pa velocity-tolerance])
                         (every? #(and (integer? %) (<= 16 % 100000))
                                 [samples-per-contact steps-per-contact])
                         (#{:backward-euler :newmark :bdf2} integration)
                         (#{:lumped :consistent} mass-model)
                         (boolean? audit-forces?)
                         (or (nil? on-sample) (ifn? on-sample)))
            (throw (ex-info "Invalid rod contact, sampling or solve settings" {})))
        gap (get settings :gap-m (* 2.0 clearance-m))
        reference (rod-impact-reference (assoc settings :gap-m gap))
        _ (when (< gap clearance-m)
            (throw (ex-info "The rod must start outside the contact potential for this reference"
                            {:gap-m gap :clearance-m clearance-m})))
        _ (when (> (:strain-scale reference) 0.001)
            (throw (ex-info "The linear rod reference requires speed/wave-speed <= 0.001"
                            {:strain-scale (:strain-scale reference)})))
        mesh (update (geometry/box-mesh divisions [width-m length-m width-m]) :points
                     #(mapv (fn [[x y z]] [(- x (* 0.5 width-m)) (+ y gap)
                                          (- z (* 0.5 width-m))]) %))
        description {:mesh mesh :material {:young-Pa young-Pa :poisson-ratio 0.0}
                     :density-kg-m3 density-kg-m3 :gravity [0.0 0.0 0.0]
                     :floor? true :friction 0.0
                     :initial-velocities (vec (repeat (count (:points mesh)) [0.0 (- speed-m-s) 0.0]))}
        sample-dt (/ (:contact-duration-s reference) samples-per-contact)
        maximum-step (/ (:contact-duration-s reference) steps-per-contact)
        duration (+ (:impact-time-s reference) (* 1.5 (:contact-duration-s reference)))
        started (System/nanoTime)
        version (joint/solver-version :ipc)
        result
        (joint/with-system! [description]
          (fn [assembly [state] _]
            (implicit/with-context! assembly {:mass-model mass-model}
              (fn [workspace]
                (let [initial (az/value (implicit/body-observables! workspace 0))
                      initial-inertia (az/value (inertia-audit state (double density-kg-m3)))
                      samples
                      (loop [time 0.0 frame 1 impulse 0.0 records []]
                        (let [target (min duration (* frame sample-dt))
                              solver-options {:velocity-tolerance velocity-tolerance :integration integration}
                              advance (if audit-forces?
                                        (advance-with-force-audit! workspace (- target time) maximum-step
                                                                   clearance-m pressure-Pa solver-options time)
                                        {:report (implicit/advance! workspace (- target time) maximum-step
                                                                    clearance-m pressure-Pa solver-options)})
                              report (:report advance)
                              observation (az/value (implicit/body-observables! workspace 0))
                              total-impulse (+ impulse (:ground-impulse report))
                              velocity (/ (get-in observation [:momentum :y]) (:mass observation))
                              record {:time-s target :observation observation :report report
                                      :fields (cond-> (az/value (apply rod-fields state divisions))
                                                (= mass-model :consistent)
                                                (assoc :transverse-kinetic-energy-mass-model :lumped))
                                      :endpoint-contact-force-N (:y (az/value (implicit/net-contact-force workspace)))
                                      :center-velocity-m-s velocity
                                      :reference-velocity-m-s (rod-reference-velocity reference target)
                                      :reference-average-contact-force-N
                                      (rod-reference-average-force reference time target)
                                      :average-contact-force-N (/ (:ground-impulse report) (- target time))
                                      :contact-impulse-N-s total-impulse}
                              record (cond-> record audit-forces?
                                       (assoc :accepted-force-steps (:accepted-force-steps advance)))
                              records (conj records record)]
                          (when on-sample (on-sample record))
                          (if (>= target duration)
                            records
                            (recur target (inc frame) total-impulse records))))]
                  {:initial initial :samples samples
                   :inertia-audit {:initial initial-inertia
                                   :final (az/value (inertia-audit state (double density-kg-m3)))}})))))]
    (when-not (= version (joint/solver-version :ipc))
      (throw (ex-info "Solver changed during the rod impact experiment" {})))
    (assoc result :settings (assoc (dissoc settings :on-sample) :gap-m gap) :reference reference
           :description description :maximum-step-s maximum-step :sample-dt-s sample-dt
           :discretization {:axial-cell-size-m (/ length-m (second divisions))
                            :cell-sizes-m (mapv / [width-m length-m width-m] divisions)
                            :cartesian-cell-aspect-ratio
                            (let [sizes (mapv / [width-m length-m width-m] divisions)]
                              (/ (apply max sizes) (apply min sizes)))
                            :courant-number (/ (* (:wave-speed-m-s reference) maximum-step)
                                                (/ length-m (second divisions)))
                            :clearance-per-axial-cell (/ clearance-m (/ length-m (second divisions)))
                            :incoming-step-travel-per-clearance (/ (* speed-m-s maximum-step) clearance-m)
                            :clearance-per-compression-scale
                            (/ clearance-m (* length-m (:strain-scale reference)))}
           :solver-version version :wall-seconds (/ (- (System/nanoTime) started) 1.0e9))))

(defn summarize-rod-impact
  "Compare measured momentum, velocity, force and energy with an external solution.
  Force errors use exact interval averages of the analytical rectangular pulse."
  [{:keys [initial samples reference]}]
  (let [final (last samples)
        observation (:observation final)
        impulse (:contact-impulse-N-s final)
        times (cons 0.0 (map :time-s samples))
        intervals (map - (rest times) times)
        force-error (reduce + (map #(* %1 (abs (- (:average-contact-force-N %2)
                                                  (:reference-average-contact-force-N %2))))
                                    intervals samples))
        active (filter #(> (:average-contact-force-N %) (* 0.01 (:contact-force-N reference))) samples)]
    {:mass-relative-error (- (/ (:mass initial) (:mass-kg reference)) 1.0)
     :maximum-relative-center-velocity-error
     (/ (apply max (map #(abs (- (:center-velocity-m-s %) (:reference-velocity-m-s %))) samples))
        (:speed-m-s reference))
     :final-rebound-speed-ratio (/ (:center-velocity-m-s final) (:speed-m-s reference))
     :force-pulse-relative-L1-error (/ force-error (:total-contact-impulse-N-s reference))
     :contact-impulse-relative-error (- (/ impulse (:total-contact-impulse-N-s reference)) 1.0)
     :momentum-balance-error-N-s (- (get-in observation [:momentum :y])
                                    (get-in initial [:momentum :y]) impulse)
     :mechanical-energy-loss-fraction
     (- 1.0 (/ (+ (:elastic-energy observation) (:kinetic-energy observation))
               (:initial-energy-J reference)))
     :minimum-jacobian (apply min (map #(get-in % [:report :minimum-jacobian]) samples))
     :minimum-sampled-height-m (apply min (map #(get-in % [:observation :minimum-height]) samples))
     :rejected-trials (reduce + (map #(get-in % [:report :rejected]) samples))
     :force-threshold-fraction 0.01
     :first-active-force-bin-end-s (:time-s (first active))
     :last-active-force-bin-end-s (:time-s (last active))}))

(defn rod-field-summary
  "These are sampled diagnostics, not bounds between output times."
  [{:keys [samples reference]}]
  {:maximum-sampled-section-velocity-spread-ratio
   (/ (apply max (map #(get-in % [:fields :maximum-section-velocity-spread]) samples))
      (:speed-m-s reference))
   :maximum-sampled-section-height-spread-m
   (apply max (map #(get-in % [:fields :maximum-section-height-spread]) samples))
   :maximum-sampled-transverse-speed-ratio
   (/ (apply max (map #(get-in % [:fields :maximum-transverse-speed]) samples))
      (:speed-m-s reference))
   :maximum-sampled-transverse-energy-fraction
   (/ (apply max (map #(get-in % [:fields :transverse-kinetic-energy]) samples))
      (:initial-energy-J reference))
   :maximum-sampled-endpoint-force-ratio
   (/ (apply max (map :endpoint-contact-force-N samples)) (:contact-force-N reference))})

(defn force-audit-summary
  "Compare accepted-step and output-bin force integrals over the same trajectory.
  Neither the endpoint maximum nor the discrete L1 norm bounds continuous force."
  [{:keys [samples reference] :as run}]
  (let [steps (vec (mapcat :accepted-force-steps samples))
        intervals (mapv #(- (:end-s %) (:begin-s %)) steps)
        impulse (reduce + 0.0 (map :impulse-N-s steps))
        error (reduce + 0.0
                      (map (fn [step dt]
                             (abs (- (:impulse-N-s step)
                                     (* dt (rod-reference-average-force reference (:begin-s step) (:end-s step))))))
                           steps intervals))
        binned (:force-pulse-relative-L1-error (summarize-rod-impact run))
        norm (/ error (:total-contact-impulse-N-s reference))]
    (when-not (and (seq steps) (every? pos? intervals)
                   (= (count steps) (reduce + (map #(get-in % [:report :substeps]) samples))))
      (throw (ex-info "Force audit has missing accepted steps" {})))
    {:accepted-steps (count steps)
     :step-average-force-relative-L1-error norm
     :output-average-force-relative-L1-error binned
     :error-hidden-by-output-averaging (- norm binned)
     :maximum-step-average-force-ratio
     (/ (apply max (map #(/ (:impulse-N-s %1) %2) steps intervals)) (:contact-force-N reference))
     :maximum-accepted-endpoint-force-ratio
     (/ (apply max (map :endpoint-force-N steps)) (:contact-force-N reference))
     :minimum-step-s (apply min intervals)
     :time-coverage-error-s (- (:time-s (last samples)) (reduce + intervals))
     :output-impulse-mismatch-N-s (- impulse (:contact-impulse-N-s (last samples)))}))

(defn compare-rod-control
  "Require one changed numerical control and identical physics, solver and outputs.
  Report sensitivity without interpreting it as a convergence rate."
  [baseline variant]
  (let [a (:settings baseline)
        b (:settings variant)
        changed (set (filter #(not= (get a %) (get b %)) (into (set (keys a)) (keys b))))
        control (first changed)]
    (when-not (and (= 1 (count changed))
                   (#{:divisions :steps-per-contact :clearance-m :pressure-Pa :velocity-tolerance :mass-model} control))
      (throw (ex-info "Rod sensitivity requires exactly one numerical control to change"
                      {:changed-settings changed})))
    (when-not (and (= (:solver-version baseline) (:solver-version variant))
                   (= (:reference baseline) (:reference variant))
                   (= (mapv :time-s (:samples baseline)) (mapv :time-s (:samples variant))))
      (throw (ex-info "Rod comparison requires identical solver, reference and output times" {})))
    {:control control :baseline (get a control) :variant (get b control)
     :summary-differences
     (into (sorted-map)
           (for [key [:force-pulse-relative-L1-error :final-rebound-speed-ratio
                      :mechanical-energy-loss-fraction :maximum-relative-center-velocity-error]]
             [key (- (get-in variant [:summary key]) (get-in baseline [:summary key]))]))}))

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

(defn rod-study!
  "Persist reproducible IPC rod cases and partial diagnostics on failure.
  Each case overrides shared settings. Existing research records are never replaced."
  [{:keys [cases output] :as options
    :or {cases [{:divisions [1 8 1]} {:divisions [1 16 1]}]
         output "exports/rod-impact-study.edn"}}]
  (when-not (and (vector? cases) (<= 1 (count cases) 16) (every? map? cases)
                 (string? output) (seq output))
    (throw (ex-info "Supply 1–16 rod case maps and an output path" {})))
  (let [mixed? (= :mixed-rod-impact (:benchmark options))
        execution (get options :execution :snapshot)
        _ (when-not (#{:snapshot :reloadable} execution)
            (throw (ex-info "Rod execution must be :snapshot or :reloadable" {:execution execution})))
        run! (if mixed? run-mixed-rod-impact! run-rod-impact!)
        summarize (if mixed? summarize-mixed-rod-impact summarize-rod-impact)
        execute! (if (= execution :reloadable) (fn [f] (f))
                     (fn [f] (joint/with-compiled-kernel! (if mixed? :mixed :ipc) f)))
        file (.getCanonicalFile (io/file (if (and mixed? (not (contains? options :output)))
                                           "exports/mixed-rod-impact-study.edn" output)))
        source (slurp "src/field_lab/impact_study.clj")
        hash (apply str (map #(format "%02x" (bit-and % 255))
                             (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                      (.getBytes source "UTF-8"))))
        record (atom {:format :pitoco/rod-impact-study-v1 :status :running
                      :started-at (str (java.time.Instant/now)) :execution execution
                      :experiment-source-sha256 hash :options options :cases []
                      :native-configuration (select-keys (az/configuration) [:optimize :reloadable?])})
        partial-samples (atom [])
        active-case (atom nil)
        write! (fn [extra]
                 (let [pending (io/file (.getParentFile file) (str ".rod-study-" (random-uuid) ".tmp"))]
                   (try
                     (spit pending (pr-str (merge @record extra)))
                     (java.nio.file.Files/move
                      (.toPath pending) (.toPath file)
                      (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                           java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                     (finally (java.nio.file.Files/deleteIfExists (.toPath pending))))))]
    (io/make-parents file)
    (java.nio.file.Files/createFile (.toPath file) (make-array java.nio.file.attribute.FileAttribute 0))
    (write! {})
    (try
      (execute!
       (fn []
         (doseq [settings cases]
           (reset! active-case (merge (dissoc options :cases :output :benchmark :execution) settings))
           (reset! partial-samples [])
           (let [result
                 (run!
                  (assoc @active-case :on-sample
                         (fn [sample]
                           (swap! partial-samples conj sample)
                           (when (zero? (mod (count @partial-samples) 32))
                             (write! {:active-case @active-case :partial-samples @partial-samples})
                             (prn {:case settings :time-s (:time-s sample)})
                             (flush)))))
                 completed (cond-> (assoc result :summary (summarize result))
                             (and (not mixed?) (every? :fields (:samples result)))
                             (assoc :field-summary (rod-field-summary result))
                             (get-in result [:settings :audit-forces?])
                             (assoc :force-audit (force-audit-summary result)))]
             (swap! record update :cases conj completed)
             (write! {})
             (when (= :failed (:status result))
               (throw (ex-info "Mixed rod solve failed; accepted prefix saved"
                               (select-keys result [:failed-time-s :failed-report]))))
             (prn {:case settings :summary (:summary completed) :wall-seconds (:wall-seconds completed)})
             (flush)))))
      (when-not (= source (slurp "src/field_lab/impact_study.clj"))
        (throw (ex-info "Rod experiment source changed during execution" {})))
      (write! {:status :completed})
      {:output (.getCanonicalPath file) :status :completed
       :summaries (mapv :summary (:cases @record))}
      (catch Throwable error
        (write! {:status :failed :message (ex-message error) :failure (ex-data error)
                 :active-case @active-case :partial-samples @partial-samples})
        (throw error)))))

(defn -main [& [options]]
  (let [settings (if options (edn/read-string options) {})]
    (when (= :mixed-rod-impact (:benchmark settings))
      (az/configure! {:optimize "ReleaseSafe"}))
    (try
      (prn (case (:benchmark settings)
             :rod-impact (rod-study! settings)
             :mixed-rod-impact (rod-study! settings)
             nil (study! settings)
             (throw (ex-info "Unknown impact benchmark" {:benchmark (:benchmark settings)}))))
      (finally (shutdown-agents)))))
