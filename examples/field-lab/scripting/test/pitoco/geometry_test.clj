(ns pitoco.geometry-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [pitoco.geometry :as geometry]))

(defn near? [a b tolerance]
  (<= (abs (- a b)) tolerance))

(deftest integrated-tetrahedron-properties
  (let [mesh {:points [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
              :cells [[0 1 2 3]]}
        values (geometry/metrics mesh)]
    (is (near? (/ 1.0 6.0) (:volume values) 1.0e-15))
    (is (= [0.25 0.25 0.25] (:centroid values)))
    (is (every? #(near? 0.2 % 1.0e-15) (:inertia-per-mass values)))
    (is (every? #(near? 0.5 % 1.0e-15) (:lumped-inertia-per-mass values)))
    (is (near? (+ 1.5 (/ (Math/sqrt 3.0) 2.0)) (:surface-area values) 1.0e-15))))

(deftest sphere-geometry-converges-to-analytic-volume-and-inertia
  (let [meshes (mapv #(geometry/sphere {:refinement %}) (range 4))
        values (mapv :metrics meshes)
        volume-errors (mapv :sphere-volume-relative-error values)
        inertia-errors (mapv #(abs (- 0.4 (first (:inertia-per-mass %)))) values)]
    (is (= [43 205 1209 8177] (mapv :nodes values)))
    (is (= [80 640 5120 40960] (mapv :tetrahedra values)))
    (is (= [80 320 1280 5120] (mapv :boundary-triangles values)))
    (is (apply > volume-errors))
    (is (apply > inertia-errors))
    (is (< (last volume-errors) 0.0022))
    (is (< (last inertia-errors) 0.00058))
    (is (every? #(< % 0.28) (map / (rest volume-errors) volume-errors)))
    (doseq [[mesh measures] (map vector meshes values)]
      (is (pos? (:minimum-signed-volume measures)))
      (is (> (:minimum-mean-ratio measures) 0.24))
      (is (every? #(< (abs %) 1.0e-13) (:centroid measures)))
      (is (< (- (apply max (:inertia-per-mass measures))
                (apply min (:inertia-per-mass measures))) 1.0e-13))
      (let [boundary (set (mapcat identity (geometry/boundary-faces mesh)))]
        (is (every? #(near? 1.0 (geometry/length ((:points mesh) %)) 1.0e-13) boundary))))))

(deftest fixed-domain-and-spherical-refinement-are-distinct
  (let [meshes (mapv #(geometry/sphere {:refinement % :geometry :polyhedron}) (range 3))
        volumes (mapv #(get-in % [:metrics :volume]) meshes)]
    (is (every? #(near? (first volumes) % 1.0e-12) volumes))
    (is (every? #(near? 0.1265467999969 (get-in % [:metrics :sphere-volume-relative-error]) 1.0e-12) meshes))
    (is (not= (:points (meshes 2)) (:points (geometry/sphere {:refinement 2}))))))

(deftest scaled-translated-source-and-topology
  (let [center [3.0 -2.0 1.0]
        unit (geometry/sphere {:refinement 1})
        scaled (geometry/sphere {:radius 0.45 :center center :refinement 1})
        a (:metrics unit) b (:metrics scaled)
        faces (geometry/boundary-faces scaled)
        boundary (set (mapcat identity faces))
        edges (frequencies (mapcat (fn [[i j k]] (map #(vec (sort %)) [[i j] [j k] [k i]])) faces))]
    (is (near? (* (:volume a) (Math/pow 0.45 3.0)) (:volume b) 1.0e-14))
    (is (near? (* (:surface-area a) 0.45 0.45) (:surface-area b) 1.0e-13))
    (is (every? true? (map #(near? %1 %2 1.0e-13) center (:centroid b))))
    (is (every? true? (map #(near? (* %1 0.45 0.45) %2 1.0e-13)
                          (:inertia-per-mass a) (:inertia-per-mass b))))
    (is (= 2 (+ (count boundary) (- (count edges)) (count faces))))
    (is (every? #{2} (vals edges)))))

(deftest invalid-source-rejected
  (doseq [options [{:radius 0.0} {:radius Double/NaN} {:center [0.0 0.0]}
                   {:center [0.0 0.0 Double/POSITIVE_INFINITY]} {:refinement 4} {:geometry :unknown}]]
    (is (thrown? clojure.lang.ExceptionInfo (geometry/sphere options))))
  (is (nil? (find-ns 'aguafria.zig))))

(defn -main [& _]
  (let [result (run-tests 'pitoco.geometry-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
