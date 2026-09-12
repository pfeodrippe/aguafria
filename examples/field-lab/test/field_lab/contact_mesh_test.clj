(ns field-lab.contact-mesh-test
  (:require [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [field-lab.contact-mesh :as contact]
            [field-lab.nonlinear-job :as job]
            [field-lab.refined-job :as refined]))

(defn prism
  "Counterclockwise polygon and its planar triangulation, extruded by one metre."
  [polygon triangles]
  (let [size (count polygon)]
    {:points (vec (for [z [0.0 1.0] [x y] polygon] [x y z]))
     :faces (vec (concat (map #(vec (reverse %)) triangles)
                         (map #(mapv (partial + size) %) triangles)
                         (mapcat (fn [a]
                                   (let [b (mod (inc a) size)]
                                     [[a b (+ b size)] [a (+ b size) (+ a size)]]))
                                 (range size))))}))

(def cube
  (prism [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]] [[0 1 2] [0 2 3]]))

(def concave
  (prism [[0.0 0.0] [2.0 0.0] [2.0 1.0] [1.0 1.0] [1.0 2.0] [0.0 2.0]]
         [[0 1 3] [1 2 3] [0 3 5] [3 4 5]]))

(defn query [surface point]
  (az/value (contact/closest-point surface (job/vector-map point))))

(defn near? [expected actual]
  (< (abs (- expected actual)) 1.0e-12))

(deftest triangle-feature-regions
  (az/await! 'field-lab.contact-mesh)
  (let [a (job/vector-map [0.0 0.0 0.0])
        b (job/vector-map [1.0 0.0 0.0])
        c (job/vector-map [0.0 1.0 0.0])]
    (doseq [[point expected weights]
            [[[0.2 0.3 2.0] 4.0 [0.5 0.2 0.3]]
             [[0.5 -1.0 0.0] 1.0 [0.5 0.5 0.0]]
             [[1.0 1.0 0.0] 0.5 [0.0 0.5 0.5]]
             [[-1.0 -1.0 0.0] 2.0 [1.0 0.0 0.0]]
             [[2.0 0.0 0.0] 1.0 [0.0 1.0 0.0]]
             [[0.0 2.0 0.0] 1.0 [0.0 0.0 1.0]]]]
      (let [result (az/value (contact/triangle-closest (job/vector-map point) a b c))]
        (is (near? expected (:squared-distance result)))
        (is (every? true? (map near? weights (job/vector-data (:weights result)))))))))

(deftest signed-features-and-refit
  (let [surface (contact/build! (:points cube) (:faces cube))]
    (try
      (doseq [[point expected]
              [[[0.5 0.5 0.5] -0.5]
               [[1.2 0.5 0.5] 0.2]
               [[1.2 1.2 0.5] (Math/sqrt 0.08)]
               [[1.2 1.2 1.2] (Math/sqrt 0.12)]
               [[1.0 0.4 0.4] 0.0]]]
        (is (near? expected (:signed-distance (query surface point)))))
      ;; Move the whole body using only incremental ancestor refits.
      (doseq [[index [x y z]] (map-indexed vector (:points cube))]
        (contact/move-point! surface index (job/vector-map [(+ x 4.0) y z])))
      (is (near? -0.5 (:signed-distance (query surface [4.5 0.5 0.5]))))
      (is (near? 3.5 (:signed-distance (query surface [0.5 0.5 0.5]))))
      (is (false? (contact/bounds-contain? surface (job/vector-map [0.5 0.5 0.5]))))
      (finally (contact/destroy! surface)))))

(deftest concave-boundary-and-bvh-pruning
  (let [{:keys [points faces]} concave
        surface (contact/build! points faces)]
    (try
      (testing "The notch is exterior, but either arm is interior"
        (is (near? 0.2 (:signed-distance (query surface [1.2 1.2 0.5]))))
        (is (near? -0.25 (:signed-distance (query surface [0.25 1.5 0.5]))))
        (is (near? -0.25 (:signed-distance (query surface [1.5 0.25 0.5])))))
      (testing "Hierarchy pruning agrees with an exhaustive nearest-triangle search"
        (doseq [x [-0.2 0.35 0.9 1.3 2.2]
                y [-0.2 0.35 0.9 1.3 2.2]
                z [-0.15 0.45 1.1]]
          (let [point (job/vector-map [x y z])
                expected (apply min (map (fn [[a b c]]
                                           (:squared-distance
                                            (az/value
                                             (contact/triangle-closest point
                                                                       (job/vector-map (points a))
                                                                       (job/vector-map (points b))
                                                                       (job/vector-map (points c)))))) faces))]
            (is (near? expected (:squared-distance (query surface [x y z])))))))
      (finally (contact/destroy! surface)))))

(deftest boundary-contract
  (let [{:keys [points faces]} cube]
    (is (true? (contact/validate! points faces)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two oppositely"
                          (contact/validate! points (pop faces))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two oppositely"
                          (contact/validate! points (update faces 0 #(vec (reverse %))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outward-oriented"
                          (contact/validate! points (mapv #(vec (reverse %)) faces))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid"
                          (contact/validate! (assoc-in points [0 0] Double/NaN) faces)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Degenerate"
                          (contact/validate! (assoc points 1 (points 0)) faces)))
    (testing "Two otherwise closed shells touching at one shared vertex are not a vertex manifold"
      (let [other (mapv #(mapv dec %) points)
            additional (vec (remove #{6} (range 8)))
            indices (assoc (zipmap additional (range 8 15)) 6 0)
            pinched-points (into points (map other additional))
            pinched-faces (into faces (map #(mapv indices %) faces))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-manifold vertex"
                              (contact/validate! pinched-points pinched-faces))))))
  (doseq [level [0 1 2]]
    (let [mesh (nth (iterate job/refine (job/sphere-mesh 0.45 [1.0 2.0 3.0])) level)]
      (is (true? (contact/validate! (:points mesh) (refined/boundary-faces mesh)))))))
