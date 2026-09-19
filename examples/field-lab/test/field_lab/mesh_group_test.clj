(ns field-lab.mesh-group-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.coupled-job :as job]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.mesh-cache-test :as mesh-test]
            [field-lab.scene :as scene]
            [field-lab.app :as app]
            [field-lab.live :as live]
            [field-lab.physics :as physics]))

(az/defn route-test-inspector!
  :- :u32 [[refinement :u32]]
  (az/set-many! app/job-contact-method 1 app/job-refinement refinement)
  (app/consume-request!)
  (app/route-inspector-bake!)
  (ak/intCast (az/field app/controls action)))

(deftest inspector-bake-uses-implicit-worker-with-physical-inputs
  ;; Requires an isolated native world; never execute against the live controller.
  (scene/initialize!)
  (try
    (let [config (merge (az/value (physics/defaults))
                        {:radius 0.2 :mass 2.0 :height 0.5 :vx 0.6 :vz -0.2 :spin 1.2 :friction 0.4})
          revision (az/value scene/revision)]
      (doseq [bodies [1 3]]
        (app/set-job-status! 1)
        (is (app/request-bake! config bodies 2 17000.0 0.025))
        (is (zero? (route-test-inspector! 1)))
        (let [command (long (app/take-job-command!))
              request (az/value (app/inspector-job-request))
              source (live/inspector-job-source request (bit-test command 3)
                                                (live/authored-job-refinement command))]
          (is (= 4 (bit-and command 7)))
          (is (= 6 (unsigned-bit-shift-right command 32)))
          (is (= config (:config request)))
          (is (= :ipc (get-in source [:bake :contact-method])))
          (is (= bodies (count (:bodies source))))
          (is (every? #(= 205 (count (get-in % [:mesh :points]))) (:bodies source)))
          (is (every? #(= 17000.0 (get-in % [:material :young-Pa])) (:bodies source)))
          (is (every? #(= 0.4 (:friction %)) (:bodies source)))
          (is (every? #(< (abs (- 2.0 (* (:density-kg-m3 %) (get-in % [:mesh :metrics :volume])))) 1.0e-12)
                      (:bodies source)))
          (is (= revision (az/value scene/revision)))
          ;; A second request while busy cannot overwrite the released snapshot
          ;; or fall through to the old per-frame coarse integrator.
          (doseq [busy-phase [3 10]]
            (app/set-job-status! busy-phase)
            (is (app/request-bake! (assoc config :mass 3.0) bodies 2 18000.0 0.05))
            (is (zero? (route-test-inspector! 2)))
            (is (zero? (app/take-job-command!)))
            (is (= request (az/value (app/inspector-job-request))))))))
    (finally
      (app/set-job-status! 0)
      (scene/shutdown!))))

(deftest solver-reload-reserves-the-mailbox-on-failure
  (try
    (app/set-job-status! 5)
    (with-redefs [field-lab.build/configure-variational!
                  (fn [] (throw (ex-info "Deliberate link failure" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Deliberate link failure"
                           (live/reload-solvers!))))
    (is (= 10 (app/job-status)))
    (is (false? (app/queue-scene-job! 1 1 true)))
    (is (zero? (app/take-job-command!)))
    (app/set-job-status! 3)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"idle attached worker"
                         (live/reload-solvers!)))
    (is (= 3 (app/job-status)))
    (finally (app/set-job-status! 0))))

(deftest authored-ball-mesh-selection
  (doseq [[level nodes cells] [[0 43 80] [1 205 640] [2 1209 5120]]
          [source bodies] [[1 1] [2 3]]]
    (let [scene (live/authored-job-source source 144 false level)
          baseline (live/authored-job-source source 144 false)]
      (is (= bodies (count (:bodies scene))))
      (is (= (:bake baseline) (:bake scene)))
      (doseq [[body original] (map vector (:bodies scene) (:bodies baseline))]
        (is (= nodes (count (get-in body [:mesh :points])) (count (:initial-velocities body))))
        (is (= cells (count (get-in body [:mesh :cells]))))
        (is (= (dissoc original :mesh :initial-velocities) (dissoc body :mesh :initial-velocities)))
        (is (every? #{(first (:initial-velocities original))} (:initial-velocities body))))))
  (is (= (live/authored-job-source 3 144 false 0) (live/authored-job-source 3 144 false 2)))
  (is (thrown? clojure.lang.ExceptionInfo (live/authored-job-source 2 144 false 3))))

(deftest authored-mesh-command-protocol
  ;; Isolated native mailbox test. Do not execute in an attached live controller.
  (try
    (doseq [level [0 1 2]]
      (app/set-job-status! 1)
      (is (app/queue-refined-scene-job! 2 144 true level))
      (is (= 2 (app/job-status)))
      (is (false? (app/queue-refined-scene-job! 1 144 false 1)))
      (let [command (long (app/take-job-command!))]
        (is (= 144 (unsigned-bit-shift-right command 32)))
        (is (= 2 (bit-and command 7)))
        (is (bit-test command 3))
        (is (= level (live/authored-job-refinement command)))))
    (app/set-job-status! 1)
    (is (false? (app/queue-refined-scene-job! 2 144 false 3)))
    (is (= 1 (app/job-status)))
    (is (app/queue-scene-job! 1 1 false))
    (is (= 1 (live/authored-job-refinement (long (app/take-job-command!)))))
    (is (= 1 (live/authored-job-refinement (bit-or (bit-shift-left 144 32) 2))))
    (finally
      (app/take-job-command!)
      (app/set-job-status! 0))))

(az/defn published-cache
  :- [:* cache/Cache]
  [[body :usize]]
  (az/unwrap (scene/mesh-cache-at body)))

(az/defn export-result
  :- :i32
  []
  (az/field app/controls exported))

(defn csv [file]
  (let [[header & lines] (str/split-lines (slurp file))
        columns (mapv keyword (str/split header #","))]
    (mapv #(zipmap columns (mapv parse-double (str/split % #","))) lines)))

(deftest synchronized-group-ownership-render-and-export
  (scene/initialize!)
  (try
    (let [result (job/bake-cache! {:refinement 1 :seconds 0.05})
          owned (:group result)
          expected (mapv #(az/value (cache/position (group/item owned %) 12 12)) (range 3))]
      (is (group/complete? owned))
      (scene/adopt-group! owned)
      (is (= 3 (az/value scene/body-count)))
      (is (= 13 (az/value scene/count)))
      (scene/seek! 12)
      (doseq [body (range 3)]
        (let [item (published-cache body)
              observation (:observation (az/value (cache/frame-info item 12)))]
          (is (= (expected body) (az/value (cache/position item 12 12))))
          (is (= (:center observation) (:position (az/value (scene/body-state body)))))))
      (is (apply < (map :x expected)))
      (is (false? (scene/step!)))
      (let [emission (az/value (mesh-test/measure-emission!))]
        (is (= 5760 (:vertices emission)))
        (is (< 0.999999 (:minimum-normal-length emission)))
        (is (> 1.000001 (:maximum-normal-length emission))))
      (scene/seek! 0)
      (scene/seek! 12)
      (is (= expected (mapv #(az/value (cache/position (published-cache %) 12 12)) (range 3))))
      (app/export!)
      (is (= 1 (export-result)))
      (let [reference (csv "exports/reference.csv")
            cells (csv "exports/cells.csv")
            particles (csv "exports/particles.csv")]
        (is (= 615 (count reference)))
        (is (= 1920 (count cells)))
        (is (= 7995 (count particles)))
        (is (= #{0.0 1.0 2.0} (set (map :body reference))))
        (is (= #{0.0 1.0 2.0} (set (map :body cells))))
        (doseq [body (range 3)]
          (let [row (first (filter #(and (= (double body) (:body %))
                                         (= 12.0 (:particle %)) (= 0.05 (:time_s %))) particles))]
            (is (some? row))
            (is (< (abs (- (:x (expected body)) (:x_m row))) 1.0e-12)))))
      (scene/set-solver! 0 10000.0)
      (is (nil? (az/value (scene/mesh-group))))
      (is (nil? (az/value (scene/mesh-cache))))
      (is (= 1 (az/value scene/count))))
    (finally (scene/shutdown!))))

(az/defn scripted? :- :bool []
  (ak/!= (scene/scripted-scene) null))

(deftest cached-solver-provenance-follows-the-published-cache
  (let [source (live/authored-job-source 1 1 false 0)
        result (job/bake-scene! source)
        owned (:group result)
        transferred? (volatile! false)
        hash (conj (vec (repeat 64 (int \a))) 0)
        parameters {:gravity [{:x 0.0 :y -9.81 :z 0.0} {:x 0.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 0.0}]
                    :floor [true false false] :source_hash hash}]
    (scene/initialize!)
    (try
      (let [revision (az/value scene/revision)]
        (is (false? (app/request-scene-with-solver! owned revision parameters 99)))
        (when-not (app/request-scene-with-solver! owned revision parameters 1)
          (throw (ex-info "Cache publication unexpectedly busy" {})))
        (vreset! transferred? true)
        (app/consume-cache!)
        (is (= :published (live/refined-result)))
        (is (= 1 (scene/scripted-solver)))
        (let [current (az/value scene/revision)
              request {:revision current :method 3 :source_hash hash}]
          (doseq [stale [(assoc request :revision (dec current))
                        (assoc request :source_hash (conj (vec (repeat 64 (int \b))) 0))]]
            (is (app/request-cached-solver! stale))
            (is (false? (app/request-cached-solver! request)))
            (app/consume-cached-solver!)
            (is (= 1 (scene/scripted-solver))))
          (is (app/request-cached-solver! request))
          (app/consume-cached-solver!)
          (is (= 3 (scene/scripted-solver)))
          (is (= current (az/value scene/revision)))
          (scene/set-scripted-scene! parameters)
          (is (zero? (scene/scripted-solver)))
          (scene/set-scripted-solver! 4)
          (scene/set-solver! 0 10000.0)
          (is (zero? (scene/scripted-solver)))
          (is (not (scripted?)))))
      (finally
        (if @transferred? (app/consume-cache!) (group/destroy! owned))
        (scene/shutdown!))))
  (doseq [[method integration code] [[:discrete nil 1] [:continuous nil 2]
                                    [:ipc :backward-euler 3] [:ipc :newmark 4] [:ipc :bdf2 5]
                                    [:ipc :unknown 0] [:unknown nil 0]]]
    (is (= code (live/scene-solver-code {:bake {:contact-method method :integration integration}}))))
  (is (= 3 (live/scene-solver-code {:bake {:contact-method :ipc}}))))

(az/defn cached-material
  :- physics/Vec3
  [[item [:* cache/Cache]]]
  (physics/v (az/field item young) (az/field item poisson) 0.0))

(deftest authored-solids-preserve-per-body-physics-and-provenance
  (let [source (load-file "scenes/solid-impact.clj")
        gravities [[0.0 -9.81 0.0] [0.3 -2.0 0.4]]
        source (-> source
                   (assoc :bake {:seconds (/ 2.0 240.0) :maximum-step 0.00005})
                   (update :bodies #(mapv (fn [body gravity] (assoc body :floor? false :gravity gravity)) % gravities)))
        result (job/bake-scene! source)
        owned (:group result)
        transferred? (volatile! false)
        time (/ 2.0 240.0)]
    (scene/initialize!)
    (try
      (is (= 2 (:bodies result)))
      (is (= [27 35] (:nodes result)))
      (is (= [48 64] (:tetrahedra result)))
      (doseq [body (range 2)]
        (let [item (group/item owned body)
              description (get-in source [:bodies body])
              gravity (gravities body)
              observation (:observation (az/value (cache/frame-info item 2)))
              expected-mass ([11.52 6.25] body)]
          (is (< (abs (- expected-mass (:mass observation))) 1.0e-12))
          (let [material (az/value (cached-material item))]
            (is (= (:material description) {:young-Pa (:x material)
                                           :poisson-ratio (:y material)})))
          (doseq [[node point] (map-indexed vector (get-in description [:mesh :points]))]
            (let [velocity (get-in description [:initial-velocities node])
                  expected (mapv #(+ %1 (* time %2) (* 0.5 time time %3)) point velocity gravity)
                  actual ((juxt :x :y :z) (az/value (cache/position item 2 node)))]
              (is (every? #(< (abs %) 1.0e-11) (map - actual expected)))))))
      (scene/adopt-group! owned)
      (vreset! transferred? true)
      (scene/set-scripted-scene! {:gravity (mapv #(zipmap [:x :y :z] %) (conj gravities [0.0 0.0 0.0]))
                                  :floor [false false false]
                                  :source_hash (conj (vec (repeat 64 (int \0))) 0)})
      (is (scripted?))
      (is (= 2 (az/value scene/body-count)))
      (scene/seek! 2)
      (app/export!)
      (is (= 1 (export-result)))
      (let [metadata (slurp "exports/experiment.json")
            masses (mapv (comp parse-double second) (re-seq #"\"mass_kg\":([0-9.eE+-]+)" metadata))
            gravity-data (mapv #(mapv parse-double (str/split (second %) #","))
                               (re-seq #"\"gravity_m_s2\":\[([^\]]+)\]" metadata))]
        (is (str/includes? metadata "pitoco/solid-scene-v1"))
        (is (every? #(< (abs %) 1.0e-12) (map - [11.52 6.25] masses)))
        (is (= gravities gravity-data))
        (is (not (str/includes? metadata "radius_m"))))
      (scene/reset! (physics/defaults))
      (is (false? (scripted?)))
      (finally
        (when-not @transferred? (group/destroy! owned))
        (scene/shutdown!)))))

(deftest unsupported-scene-input-fails-before-native-allocation
  (let [source (load-file "scenes/solid-impact.clj")]
    (doseq [invalid [(assoc source :format :unknown)
                     (assoc source :bodies [])
                     (assoc source :bodies (vec (repeat 4 (first (:bodies source)))))
                     (assoc-in source [:bodies 1 :id] :soft-box)
                     (assoc-in source [:bake :seconds] 0.001)
                     (assoc-in source [:bake :maximum-step] Double/NaN)
                     (assoc-in source [:bake :frame-rate] 60)
                     (assoc-in source [:bake :contact-method] :continous)
                     (assoc-in source [:bake :clearance] 0.0)
                     (assoc-in source [:bodies 0 :id] nil)
                     (update-in source [:bodies 0 :mesh :points]
                                #(mapv (fn [[x _ z]] [x 0.0 z]) %))
                     (assoc-in source [:bodies 0 :constraints] [[0 0 0.0]])]]
      (is (thrown? clojure.lang.ExceptionInfo (job/normalize-scene invalid))))))
