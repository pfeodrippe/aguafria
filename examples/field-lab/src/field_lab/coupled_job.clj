(ns field-lab.coupled-job
  "Clojure-authored scopes for multiple interacting finite-deformation solids."
  (:require [clojure.java.io :as io]
            [aguafria.zig :as az]
            [field-lab.coupled-fem :as coupled]
            [field-lab.contact-mesh :as contact]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.nonlinear-job :as job]
            [field-lab.physics :as p]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.fem-job :as linear]
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
                            faces (geometry/boundary-faces (:mesh description))
                            surface (contact/build! points faces)]
                        (try
                          (coupled/attach! assembly index state surface)
                          (attach (inc index) (conj states state))
                          (finally (contact/destroy! surface))))))))]
        (attach 0 []))
      (finally (coupled/destroy! assembly)))))

(defn advance!
  "Advance one owned frame in bounded native batches. Cancellation and progress
  callbacks run on the host between batches; accepted states remain resumable."
  ([assembly seconds maximum-step] (advance! assembly seconds maximum-step {}))
  ([assembly seconds maximum-step
    {:keys [maximum-attempts cancelled? on-progress]
     :or {maximum-attempts 8 cancelled? (constantly false)}}]
   (when-not (and (number? seconds) (Double/isFinite (double seconds)) (<= 0.0 seconds)
                  (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step)
                  (integer? maximum-attempts) (<= 1 maximum-attempts 64)
                  (ifn? cancelled?) (or (nil? on-progress) (ifn? on-progress)))
     (throw (ex-info "Invalid explicit FEM duration, step, work budget or callbacks" {})))
   (let [check-cancellation!
         (fn [accepted]
           (when (or (.isInterrupted (Thread/currentThread)) (cancelled?))
             (throw (ex-info "Explicit FEM bake cancelled at its last accepted state"
                             {:cancelled? true :report accepted}))))]
     (check-cancellation! nil)
     (let [task (coupled/create-explicit-task! assembly (double seconds) (double maximum-step))]
       (try
         (loop [accepted nil]
           (check-cancellation! accepted)
           (let [report (merge (az/value (coupled/advance-explicit-batch! task maximum-attempts))
                               (az/value (coupled/explicit-progress task)))]
             (when on-progress (on-progress report))
             (cond
               (:completed report) report
               (zero? (:status report)) (recur report)
               :else (throw (ex-info "Coupled integration stopped at its last accepted all-body state" report)))))
         (finally
           (let [interrupted? (Thread/interrupted)]
             (try
               (coupled/destroy-explicit-task! task)
               (finally
                 (when interrupted? (.interrupt (Thread/currentThread))))))))))))

(defn advance-continuous-bounded!
  "Run at most maximum-attempts FEM drift attempts. Budget exhaustion returns a
  resumable last-accepted state; numerical failure still throws with diagnostics."
  [assembly seconds maximum-step clearance maximum-attempts]
  (when-not (and (number? seconds) (Double/isFinite (double seconds)) (<= 0.0 seconds)
                 (number? maximum-step) (Double/isFinite (double maximum-step)) (pos? maximum-step)
                 (number? clearance) (Double/isFinite (double clearance)) (< 1.0e-12 clearance) (<= clearance 0.01)
                 (integer? maximum-attempts) (<= 0 maximum-attempts 4294967295))
    (throw (ex-info "Invalid continuous integration duration, step, clearance or attempt budget" {})))
  (let [report (az/value (coupled/advance-continuous-bounded! assembly (double seconds) (double maximum-step)
                                                           (double clearance) maximum-attempts))]
    (when-not (or (:completed report) (:budget-exhausted report))
      (throw (ex-info "Continuous FEM integration stopped at its last accepted state" report)))
    report))

(defn advance-continuous!
  "Advance an owned assembly through native FEM and CCD-checked contact drifts.
  Initial disjointness is required. Numerical clearance needs convergence study."
  ([assembly seconds maximum-step] (advance-continuous! assembly seconds maximum-step 1.0e-5))
  ([assembly seconds maximum-step clearance]
   (let [report (advance-continuous-bounded! assembly seconds maximum-step clearance 4294967295)]
     (when-not (:completed report)
       (throw (ex-info "Continuous FEM integration exhausted its attempt budget" report)))
     report)))

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

(defn solver-version
  ([] (solver-version :discrete))
  ([contact-method]
   (merge (when (= contact-method :ipc) ((requiring-resolve 'field-lab.variational/solver-version)))
          (job/cache-solver-version)
         {'field-lab.contact-mesh/native-library (contact/ccd-version)}
         (into (sorted-map)
               (for [module ['field-lab.contact-mesh 'field-lab.coupled-fem]
                     :let [info (az/module-info module)]]
                 [module (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
                               (sort-by (comp pr-str :logical-id) (:definitions info)))])))))

(defn with-integrator!
  "Own optional solver scratch for an entire bake; f receives advance(seconds)."
  [assembly {:keys [contact-method maximum-step clearance barrier-pressure]
             :or {barrier-pressure 1000.0} :as options} f]
  (case contact-method
    :ipc ((requiring-resolve 'field-lab.variational/with-context!)
          assembly
          (fn [workspace]
            (let [advance (requiring-resolve 'field-lab.variational/advance!)]
              (f #(advance workspace % maximum-step clearance barrier-pressure
                            (select-keys options [:cancelled? :on-progress]))))))
    :continuous (f #(advance-continuous! assembly % maximum-step clearance))
    :discrete (f #(advance! assembly % maximum-step
                           (select-keys options [:cancelled? :on-progress])))))

(defn head-on-study!
  "Reproducible isolated two/three-solid impacts with momentum and contact residuals.
  This measures the selected numerical contact method, not experimental material accuracy."
  [{:keys [bodies refinement seconds maximum-step friction geometry contact-method clearance barrier-pressure]
    :or {bodies 3 refinement 1 seconds 0.06 maximum-step 0.00005 friction 0.0 geometry :polyhedron
         contact-method :discrete clearance 1.0e-5 barrier-pressure 1000.0}}]
  (when-not (and (#{2 3} bodies) (#{:discrete :continuous :ipc} contact-method)
                 (number? clearance) (Double/isFinite (double clearance)) (< 1.0e-12 clearance) (<= clearance 0.01)
                 (number? barrier-pressure) (Double/isFinite (double barrier-pressure)) (< 0.0 barrier-pressure 1.0e12)
                 (number? seconds)
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
        version (solver-version contact-method)
        started (System/nanoTime)]
    (with-system! descriptions
      (fn [assembly states _]
        (with-integrator! assembly {:contact-method contact-method :maximum-step maximum-step
                                    :clearance clearance :barrier-pressure barrier-pressure}
          (fn [advance-frame!]
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
                                    (try
                                      (swap! reports conj (advance-frame! 0.001))
                                      (catch clojure.lang.ExceptionInfo error
                                        (throw (ex-info (.getMessage error)
                                                        (assoc (ex-data error)
                                                               :descriptions descriptions :solver-version version
                                                               :checkpoint (mapv (fn [state description]
                                                                                   (job/snapshot state (count (get-in description [:mesh :points]))))
                                                                                 states descriptions)) error))))
                                    (observe (/ tick 1000.0)))))]
              (when (not= version (solver-version contact-method))
                (throw (ex-info "Solver changed during coupled study; rerun with the new version" {})))
              {:descriptions descriptions :solver-version version :maximum-step maximum-step
               :contact-method contact-method :clearance clearance :history history :reports @reports :barrier-pressure barrier-pressure :elapsed-seconds (/ (- (System/nanoTime) started) 1.0e9)})))))))

(defn benchmark!
  "Measure repeated isolated bakes, retaining physical results and compiler provenance.
  Compilation is awaited before timing. This never publishes to the viewport."
  [{:keys [repetitions output] :or {repetitions 3} :as options}]
  (when-not (and (integer? repetitions) (<= 1 repetitions 9))
    (throw (ex-info "Use between one and nine benchmark repetitions" {})))
  (let [settings (merge {:bodies 2 :refinement 0 :seconds 0.012 :maximum-step 0.0001
                         :contact-method :ipc :clearance 0.0001 :barrier-pressure 1000.0}
                        (dissoc options :repetitions :output))
        modules (cond-> ['field-lab.physics 'field-lab.hyperelastic 'field-lab.fem
                          'field-lab.nonlinear-fem 'field-lab.contact-mesh 'field-lab.coupled-fem]
                  (= :ipc (:contact-method settings)) (conj 'field-lab.variational))]
    (when (= :ipc (:contact-method settings))
      (require 'field-lab.variational))
    (doseq [module modules]
      (az/await! module))
    (let [runs (mapv (fn [_] (head-on-study! settings)) (range repetitions))
          times (vec (sort (map :elapsed-seconds runs)))
          middle (quot repetitions 2)
          median (if (odd? repetitions) (times middle)
                     (* 0.5 (+ (times (dec middle)) (times middle))))
          result {:format :pitoco/bake-benchmark-v1
                  :options settings :repetitions repetitions
                  :wall-seconds (mapv :elapsed-seconds runs)
                  :median-wall-seconds median
                  :wall-seconds-per-simulated-second (/ median (:seconds settings))
                  :repeat-identical? (apply = (map #(select-keys % [:history :reports]) runs))
                  :host {:os (System/getProperty "os.name") :arch (System/getProperty "os.arch")
                         :processors (.availableProcessors (Runtime/getRuntime))}
                  :configuration (select-keys (az/configuration) [:optimize :reloadable? :cpu])
                  :compiler-commands (into {} (map (fn [module] [module (:command (az/module-info module))]) modules))
                  :runs runs}]
      (when-not (apply = (map :solver-version runs))
        (throw (ex-info "Solver changed between benchmark repetitions" {})))
      (when output
        (io/make-parents output)
        (spit output (pr-str result)))
      result)))

(defn normalize-scene
  "Validate an EDN/Clojure solid scene against the current native cache capabilities."
  [{:keys [format bodies bake] :as scene}]
  (when-not (and (= format :pitoco/solid-scene-v1) (vector? bodies) (<= 1 (count bodies) 3))
    (throw (ex-info "Expected :pitoco/solid-scene-v1 with one to three solid bodies" {})))
  (when (or (seq (remove #{:format :title :bodies :bake} (keys scene)))
            (and bake (not (map? bake)))
            (seq (remove #{:seconds :maximum-step :contact-method :clearance :barrier-pressure} (keys bake))))
    (throw (ex-info "Unknown or malformed scene/bake field" {})))
  (let [{:keys [seconds maximum-step contact-method clearance barrier-pressure] :as integration}
        (merge {:seconds 1.0 :maximum-step 0.0001 :contact-method :discrete :clearance 1.0e-5 :barrier-pressure 1000.0} bake)
        finite? #(and (number? %) (Double/isFinite (double %)))]
    (when-not (and (finite? seconds) (<= (/ 1.0 240.0) seconds 60.0)
                   (< (abs (- (* seconds 240.0) (Math/rint (* seconds 240.0)))) 1.0e-8)
                   (finite? maximum-step) (pos? maximum-step)
                   (#{:discrete :continuous :ipc} contact-method)
                   (finite? clearance) (< 1.0e-12 clearance) (<= clearance 0.01)
                   (finite? barrier-pressure) (< 0.0 barrier-pressure) (<= barrier-pressure 1.0e12))
      (throw (ex-info "Scene bake needs whole 240 Hz output ticks and a finite positive step cap" {})))
    (let [descriptions (mapv (fn [index description]
                               (let [body (job/normalize-description description)
                                     nodes (count (get-in body [:mesh :points]))
                                     cells (count (get-in body [:mesh :cells]))]
                                 (when-not (and (<= nodes 20000) (<= cells 160000)
                                                (empty? (:constraints body)))
                                   (throw (ex-info "Coupled cache capacity or free-body constraint exceeded"
                                                   {:body index :nodes nodes :cells cells})))
                                 (let [id (get body :id (keyword (str "body-" index)))]
                                   (when-not (or (keyword? id) (and (string? id) (seq id)))
                                     (throw (ex-info "Body ID must be a keyword or nonempty string" {:body index})))
                                   (assoc body :id id))))
                             (range) bodies)
          frames (inc (long (Math/rint (* seconds 240.0))))
          nodes (reduce + (map #(count (get-in % [:mesh :points])) descriptions))]
      (when-not (= (count descriptions) (count (set (map :id descriptions))))
        (throw (ex-info "Body IDs must be unique within a scene" {})))
      (when (> (* frames nodes) 8000000)
        (throw (ex-info "Scene exceeds the total 384 MB particle-storage budget" {})))
      (assoc scene :bodies descriptions :bake integration))))

(defn- presentation-config
  "Compatibility data for the current viewport; physics comes from the body description."
  [description]
  (let [mesh (:mesh description)
        points (:points mesh)
        metrics (geometry/metrics mesh)
        center (:centroid metrics)
        radius (apply max (map #(geometry/length (geometry/subtract % center)) points))]
    (merge (az/value (p/defaults))
           {:radius radius :mass (* (:density-kg-m3 description) (:volume metrics))
            :height (apply min (map second points))
            :gravity (- (second (:gravity description)))
            :friction (:friction description) :vx 0.0 :vz 0.0 :spin 0.0})))

(defn- bake-scene* [source legacy-settings options]
  (let [{:keys [bodies bake] :as scene} (normalize-scene source)
        {:keys [seconds maximum-step contact-method clearance barrier-pressure]} bake
        frames (inc (long (Math/rint (* seconds 240.0))))
        nodes (mapv #(count (get-in % [:mesh :points])) bodies)
        boundaries (mapv #(geometry/boundary-faces (:mesh %)) bodies)
        configs (if legacy-settings (vec (repeat (count bodies) legacy-settings))
                    (mapv presentation-config bodies))]
    (when (or (some #(> (count %) 80000) boundaries)
              (> (+ 3 (* 6 (reduce + (map count boundaries)))) 524288))
      (throw (ex-info "Scene exceeds the current viewport triangle-stream capacity" {})))
    (doseq [module ['field-lab.mesh-cache 'field-lab.mesh-group 'field-lab.coupled-fem]]
      (az/await! module))
    (let [version (solver-version contact-method)]
      (with-system! bodies
        (fn [assembly states _]
          (with-integrator! assembly (merge bake (select-keys options [:cancelled? :on-progress]))
            (fn [advance-frame!]
              (let [owned (group/create!)
                    reports (atom [])]
                (try
                  (doseq [[description node-count faces config] (map vector bodies nodes boundaries configs)]
                    (let [{:keys [young-Pa poisson-ratio]} (:material description)
                          item (cache/create! node-count (count (get-in description [:mesh :cells]))
                                              (count faces) frames config (double young-Pa) (double poisson-ratio))]
                      (group/add! owned item)
                      (doseq [[index [a b c]] (map-indexed vector faces)]
                        (cache/set-face! item index a b c))))
                  (doseq [[body state] (map-indexed vector states)]
                    (cache/record! (group/item owned body) state 0.0))
                  (dotimes [tick (dec frames)]
                    (when (or (.isInterrupted (Thread/currentThread))
                              (when-let [cancelled? (:cancelled? options)] (cancelled?)))
                      (throw (ex-info "Scene bake cancelled before publication" {:tick tick :cancelled? true})))
                    (swap! reports conj (advance-frame! (/ 1.0 240.0)))
                    (doseq [[body state] (map-indexed vector states)]
                      (cache/record! (group/item owned body) state (/ (inc tick) 240.0)))
                    (when-let [on-frame (:on-frame options)]
                      (on-frame {:tick (inc tick) :frames frames :report (peek @reports)})))
                  (when (not= version (solver-version contact-method))
                    (throw (ex-info "Solver changed during scene bake; rerun with the new version" {})))
                  {:group owned :scene scene :frames frames :bodies (count bodies) :nodes nodes
                   :tetrahedra (mapv #(count (get-in % [:mesh :cells])) bodies)
                   :descriptions bodies :configs configs :config (first configs)
                   :solver-version version :maximum-step maximum-step :reports @reports}
                  (catch Throwable error
                    (group/destroy! owned)
                    (throw error)))))))))))

(defn bake-scene!
  "Bake authored solid meshes/materials/initial conditions into an owned cache group.
  Caller must publish the group or destroy it; no live scene is mutated here."
  ([scene] (bake-scene! scene {}))
  ([scene options]
   (doseq [key [:cancelled? :on-progress :on-frame]]
     (when (and (contains? options key) (not (ifn? (get options key))))
       (throw (ex-info "Job callbacks must be functions" {:key key}))))
   (bake-scene* scene nil options)))

(defn bake-cache!
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
                                    description (sphere
                                                 {:refinement refinement :radius radius :center center
                                                  :velocity velocity :young stiffness
                                                  :geometry geometry
                                                  :gravity [0.0 (- gravity) 0.0] :floor? true :friction friction})]
                                (assoc description
                                       :density-kg-m3 (/ mass (get-in description [:mesh :metrics :volume]))
                                       :initial-velocities
                                       (mapv #(mapv + velocity (linear/cross [0.0 spin 0.0] (linear/subtract % center)))
                                             (get-in description [:mesh :points]))))) [-1.0 0.0 1.0])
        result (bake-scene* {:format :pitoco/solid-scene-v1
                             :bodies descriptions
                             :bake {:seconds seconds :maximum-step maximum-step}} settings {})]
    (assoc result :config settings)))

(defn normal-impulses!
  "Solve A*impulse + velocity >= 0 with nonnegative complementary impulses.
  A must be a symmetric PSD contact matrix, normally J M^-1 J^T.
  Returns data only; no particle velocities are mutated. This is frictionless."
  [matrix velocity]
  (let [size (count velocity)
        finite? #(and (number? %) (Double/isFinite (double %)))]
    (when-not (and (vector? velocity) (<= 1 size 128) (every? finite? velocity)
                   (vector? matrix) (= size (count matrix))
                   (every? #(and (vector? %) (= size (count %)) (every? finite? %)) matrix))
      (throw (ex-info "Normal solve requires a finite square system of 1–128 constraints" {})))
    (let [owned (coupled/create-normal-system! size)]
      (try
        (doseq [i (range size)]
          (coupled/set-normal-rhs! owned i (double (velocity i)))
          (doseq [j (range size)]
            (coupled/set-normal-entry! owned i j (double (get-in matrix [i j])))))
        (let [report (az/value (coupled/solve-normal-system! owned))]
          (when-not (zero? (:status report))
            (throw (ex-info "Coupled normal solve did not satisfy complementarity" report)))
          {:report report :impulses (mapv #(coupled/normal-impulse owned %) (range size))})
        (finally (coupled/destroy-normal-system! owned))))))
