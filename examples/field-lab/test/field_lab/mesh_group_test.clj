(ns field-lab.mesh-group-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [aguafria.zig :as az]
            [field-lab.coupled-job :as job]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.mesh-cache-test :as mesh-test]
            [field-lab.scene :as scene]
            [field-lab.app :as app]
            [field-lab.physics :as physics]))

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
