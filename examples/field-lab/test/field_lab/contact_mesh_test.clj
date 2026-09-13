(ns field-lab.contact-mesh-test
  (:require [pitoco.geometry :as geometry]
            [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [field-lab.contact-mesh :as contact]
            [field-lab.nonlinear-job :as job]))

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

(deftest rotated-face-normal-at-vanishing-gaps
  (let [rotate (fn [[x y z]]
                 [(+ 0.123 (- (* 0.8 x) (* 0.6 y)))
                  (+ 0.47 (- (* 0.6 (+ (* 0.6 x) (* 0.8 y))) (* 0.8 z)))
                  (+ -0.27 (* 0.8 (+ (* 0.6 x) (* 0.8 y))) (* 0.6 z))])
        surface (contact/build! (mapv rotate (:points cube)) (:faces cube))
        expected [0.8 0.36 0.48]]
    (try
      (doseq [gap [1.0e-3 1.0e-6 1.0e-9 1.0e-12 -1.0e-12 0.0]]
        (let [result (query surface (rotate [(+ 1.0 gap) 0.37 0.41]))
              normal ((juxt :x :y :z) (:normal result))]
          (is (every? true? (map near? expected normal)))
          (is (near? gap (:signed-distance result)))))
      (doseq [local [[1.2 1.4 0.41] [1.2 1.4 1.8]]]
        (let [closest (mapv #(min 1.0 %) local)
              direction (geometry/normalize (mapv - local closest))
              expected (mapv - (rotate direction) (rotate [0.0 0.0 0.0]))
              result (query surface (rotate local))]
          (is (every? true? (map near? expected ((juxt :x :y :z) (:normal result)))))))
      (finally (contact/destroy! surface)))))

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
      (is (true? (contact/validate! (:points mesh) (geometry/boundary-faces mesh)))))))

(def crossing-face-start
  [[0.2 0.2 1.0] [0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]])

(def crossing-face-end
  (assoc-in crossing-face-start [0 2] -1.0))

(def crossing-edge-start
  [[-1.0 0.0 0.0] [1.0 0.0 0.0] [0.0 -1.0 1.0] [0.0 1.0 1.0]])

(def crossing-edge-end
  (-> crossing-edge-start (assoc-in [2 2] -1.0) (assoc-in [3 2] -1.0)))

(deftest continuous-feature-crossings
  (doseq [[kind start end] [[:vertex-face crossing-face-start crossing-face-end]
                           [:edge-edge crossing-edge-start crossing-edge-end]]
          shift [[0.0 0.0 0.0] [4.0 -2.0 3.0]]]
    ;; Apply a common translation and a common time-dependent velocity.
    (let [start (mapv #(mapv + % shift) start)
          end (mapv #(mapv + % shift [0.7 -0.3 0.9]) end)
          result (contact/sweep! kind start end)]
      (is (:possible-contact? result))
      (is (<= 0.0 (:time result) 0.5))
      (is (< (- 0.5 (:time result)) 1.0e-5))
      (is (false? (:precision-limited? result)))
      (is (false? (:possible-contact? (contact/sweep! kind start end {:maximum-time 0.4})))))))

(deftest continuous-feature-misses-and-degeneracies
  (let [outside-start (assoc crossing-face-start 0 [1.1 1.1 1.0])
        outside-end (assoc crossing-face-end 0 [1.1 1.1 -1.0])
        miss (contact/sweep! :vertex-face outside-start outside-end)]
    (is (false? (:possible-contact? miss)))
    (is (= Double/POSITIVE_INFINITY (:time miss))))
  (testing "Initial contact is reported at zero, including collapsed edges"
    (doseq [points [crossing-edge-start
                    [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]]]]
      (let [points (mapv #(assoc % 2 0.0) points)
            result (contact/sweep! :edge-edge points points)]
        (is (:possible-contact? result))
        (is (zero? (:time result))))))
  (testing "Coplanar parallel edges meet between two separated endpoint states"
    (let [start [[-1.0 0.0 0.0] [1.0 0.0 0.0] [-0.5 1.0 0.0] [0.5 1.0 0.0]]
          end (-> start (assoc-in [2 1] -1.0) (assoc-in [3 1] -1.0))
          result (contact/sweep! :edge-edge start end)]
      (is (:possible-contact? result))
      (is (<= (:time result) 0.5))
      (is (<= (* 2.0 (- 0.5 (:time result))) (+ 1.0e-12 (:achieved-tolerance result))))))
  (let [result (contact/sweep! :vertex-face crossing-face-start crossing-face-end {:separation 0.1})]
    (is (:possible-contact? result))
    (is (<= (:time result) 0.45))
    (is (<= (* 2.0 (- 0.45 (:time result))) (+ 1.0e-12 (:achieved-tolerance result))))))

(deftest continuous-query-limits
  (let [limited (contact/sweep! :vertex-face crossing-face-start crossing-face-end {:maximum-iterations 1})]
    (is (:possible-contact? limited))
    (is (:precision-limited? limited))
    (is (<= (:time limited) 0.5)))
  (doseq [options [{:tolerance 0.0} {:separation -0.1} {:maximum-time 0.0}
                   {:maximum-time 1.1} {:maximum-iterations 0} {:maximum-iterations 1000001}
                   {:tolerance Double/NaN} {:separation 1.0} {:tolerence 1.0e-10} []]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (contact/sweep! :vertex-face crossing-face-start crossing-face-end options))))
  (is (thrown? clojure.lang.ExceptionInfo
               (contact/sweep! :vertex-face (assoc-in crossing-face-start [0 0] Double/NaN) crossing-face-end)))
  (is (thrown? clojure.lang.ExceptionInfo
               (contact/sweep! :unknown crossing-face-start crossing-face-end))))

(def interlaced-triangle
  {:start [[-2.0 -1.0 0.0] [2.0 -1.0 0.0] [0.0 2.0 0.0]]
   :end [[-2.0 -1.0 0.0] [2.0 -1.0 0.0] [0.0 2.0 0.0]]
   :faces [[0 1 2]]})

(def passing-triangle
  {:start [[-2.0 1.0 1.0] [2.0 1.0 1.0] [0.0 -2.0 1.0]]
   :end [[-2.0 1.0 -1.0] [2.0 1.0 -1.0] [0.0 -2.0 -1.0]]
   :faces [[0 1 2]]})

(defn translate-motion [mesh shift]
  (reduce (fn [mesh key] (update mesh key #(mapv (fn [point] (mapv + point shift)) %)))
          mesh [:start :end]))

(defn combine-motions [a b]
  {:start (into (:start a) (:start b))
   :end (into (:end a) (:end b))
   :faces (into (:faces a) (map #(mapv (partial + (count (:start a))) %) (:faces b)))})

(deftest whole-mesh-edge-crossing
  (testing "Separated endpoint states, and every vertex misses the other triangle"
    (doseq [[a b] [[interlaced-triangle passing-triangle] [passing-triangle interlaced-triangle]]]
      (doseq [vertex (range 3)]
        (is (false? (:possible-contact?
                     (contact/sweep! :vertex-face
                                     (into [((:start a) vertex)] (:start b))
                                     (into [((:end a) vertex)] (:end b))))))))
    (doseq [shift [[0.0 0.0 0.0] [4.0 -2.0 3.0]]
            [a b] [[interlaced-triangle passing-triangle] [passing-triangle interlaced-triangle]]]
      (let [result (contact/sweep-meshes! (translate-motion a shift) (translate-motion b shift))]
        (is (:possible-contact? result))
        (is (= :edge-edge (get-in result [:feature :kind])))
        (is (<= 0.0 (:time result) 0.5))
        (is (< (- 0.5 (:time result)) 1.0e-5))
        (is (= [0 0] (:faces result)))))))

(deftest whole-mesh-hierarchy-and-motion-refit
  (let [early (-> passing-triangle
                  (update :start #(mapv (fn [p] (assoc p 2 0.5)) %))
                  (update :end #(mapv (fn [p] (assoc p 2 -1.5)) %)))
        late (-> passing-triangle
                 (update :start #(mapv (fn [p] (assoc p 2 1.5)) %))
                 (update :end #(mapv (fn [p] (assoc p 2 -0.5)) %)))
        a (combine-motions interlaced-triangle (translate-motion interlaced-triangle [10.0 0.0 0.0]))
        b (combine-motions late (translate-motion early [10.0 0.0 0.0]))
        result (contact/sweep-meshes! a b)]
    (is (:possible-contact? result))
    (is (= [1 1] (:faces result)))
    (is (<= 0.0 (:time result) 0.25))
    (is (< (- 0.25 (:time result)) 1.0e-5))
    (is (= 2 (:candidates result)))
    (testing "Prune an entire separated swept hierarchy within one node visit"
      (let [clear (contact/sweep-meshes! a (translate-motion b [100.0 0.0 0.0]) {:maximum-work 1})]
        (is (false? (:possible-contact? clear)))
        (is (= Double/POSITIVE_INFINITY (:time clear)))
        (is (= 1 (:visits clear)))
        (is (zero? (:queries clear)))))
    (testing "A single moving vertex expands the swept boxes beyond either static face location"
      (let [deforming (assoc passing-triangle :end [[-2.0 1.0 1.0] [2.0 1.0 1.0] [0.0 -2.0 -3.0]])
            result (contact/sweep-meshes! interlaced-triangle deforming)]
        (is (:possible-contact? result))
        ;; At y=-1, z=1-(8/3)t along either sloping edge, so contact is t=3/8.
        (is (<= (:time result) 0.375))
        (is (< (- 0.375 (:time result)) 1.0e-5))))))

(deftest whole-mesh-query-contract
  (testing "Work exhaustion is explicit, including after a possible hit was already found"
    (doseq [work [1 8 12]]
      (let [failure (try (contact/sweep-meshes! interlaced-triangle passing-triangle {:maximum-work work})
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= :work-budget (:reason failure)))
        (is (= 4 (:status failure))))))
  (testing "Minimum separation and initial contact remain conservative"
    (let [result (contact/sweep-meshes! interlaced-triangle passing-triangle {:separation 0.1})]
      (is (:possible-contact? result))
      (is (<= (:time result) 0.45))
      (is (<= (* 2.0 (- 0.45 (:time result)))
              (+ 1.0e-12 (:achieved-tolerance result)))))
    (let [coplanar (assoc passing-triangle :start (:end interlaced-triangle))
          result (contact/sweep-meshes! interlaced-triangle coplanar)]
      (is (:possible-contact? result))
      (is (zero? (:time result)))))
  (testing "Invalid endpoints cannot be hidden behind an otherwise disjoint box"
    (doseq [mesh [(assoc-in passing-triangle [:end 0 0] Double/NaN)
                 (assoc-in passing-triangle [:end 0 0] 1.0e51)
                 (update passing-triangle :end pop)
                 (assoc passing-triangle :faces [[0 0 1]])
                 (assoc passing-triangle :faces [[0 1 3]])
                 (assoc passing-triangle :typo true)]]
      (is (thrown? clojure.lang.ExceptionInfo (contact/sweep-meshes! interlaced-triangle mesh)))))
  (doseq [options [{:maximum-work 0} {:maximum-work 10000001} {:maximum-iterations 0}
                   {:tolerance 0.0} {:separation -1.0} {:tolerence 0.1} []]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (contact/sweep-meshes! interlaced-triangle passing-triangle options))))
  (testing "Mutating a native endpoint invalidates its refit, including nonfinite endpoints"
    (contact/with-motion! interlaced-triangle
      (fn [a]
        (contact/with-motion! passing-triangle
          (fn [b]
            (contact/set-motion-point! b 0 (job/vector-map [-2.0 1.0 1.0])
                                       (job/vector-map [-2.0 1.0 -1.0]))
            (is (= 2 (:status (az/value (contact/sweep-surfaces a b 0.0 1.0e-8 1000 1000000)))))
            (is (true? (contact/refit-motion! b)))
            (is (= 1 (:status (az/value (contact/sweep-surfaces a b 0.0 1.0e-8 1000 1000000)))))
            (contact/set-motion-point! b 0 (job/vector-map [Double/NaN 1.0 1.0])
                                       (job/vector-map [-2.0 1.0 -1.0]))
            (is (false? (contact/refit-motion! b)))
            (is (= 2 (:status (az/value (contact/sweep-surfaces a b 0.0 1.0e-8 1000 1000000)))))))))))

(deftest swept-box-outward-rounding
  (az/await! 'field-lab.contact-mesh)
  (let [upper 0.1
        separation 0.7
        rounded (+ upper separation)
        adjacent (Math/nextUp rounded)
        box (fn [x] {:lower (job/vector-map [x 0.0 0.0])
                      :upper (job/vector-map [x 0.0 0.0])})]
    (is (true? (contact/boxes-near? (box upper) (box adjacent) separation)))
    (is (true? (contact/boxes-near? (box adjacent) (box upper) separation)))
    (is (false? (contact/boxes-near? (box upper) (box (Math/nextUp adjacent)) separation)))))

(deftest closest-segment-features
  (doseq [[points expected]
          [[[[0.0 0.0 0.0] [2.0 0.0 0.0] [0.5 -1.0 0.3] [0.5 1.0 0.3]] 0.09]
           [[[0.0 0.0 0.0] [2.0 0.0 0.0] [0.5 0.3 0.0] [1.5 0.3 0.0]] 0.09]
           [[[0.0 0.0 0.0] [2.0 0.0 0.0] [3.0 1.0 0.0] [3.0 2.0 0.0]] 2.0]
           [[[0.0 0.0 0.0] [0.0 0.0 0.0] [-1.0 0.3 0.0] [1.0 0.3 0.0]] 0.09]
           [[[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.3] [0.0 0.0 0.3]] 0.09]
           [[[-1.0 0.0 0.0] [1.0 0.0 0.0] [-1.0 -1.0e-9 0.3] [1.0 1.0e-9 0.3]] 0.09]]
          reorder [identity (fn [[a b c d]] [b a d c]) (fn [[a b c d]] [c d a b])]]
    (let [result (az/value (apply contact/segment-closest (map job/vector-map (reorder points))))]
      (is (near? expected (:squared-distance result)))
      (is (<= 0.0 (:s result) 1.0))
      (is (<= 0.0 (:t result) 1.0)))))


(deftest certified-linear-feature-separation
  (doseq [[kind points] [[:vertex-face [[0.2 0.2 1.0e-12] [0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]]]
                         [:edge-edge [[-1.0 0.0 0.0] [1.0 0.0 0.0] [-0.5 1.0e-12 0.0] [0.5 1.0e-12 0.0]]]]]
    (let [end (mapv #(mapv + % [0.3 0.2 -0.1]) points)
          separated (contact/sweep! kind points end {:maximum-iterations 1})
          thickness (contact/sweep! kind points end {:separation 1.0e-6})]
      (is (:separation-certified? separated))
      (is (false? (:possible-contact? separated)))
      (is (:possible-contact? thickness))
      (is (false? (:separation-certified? thickness)))))
  (doseq [[kind start end] [[:vertex-face crossing-face-start crossing-face-end]
                           [:edge-edge crossing-edge-start crossing-edge-end]]]
    (let [result (contact/sweep! kind start end)]
      (is (:possible-contact? result))
      (is (false? (:separation-certified? result))))))

(deftest certified-coplanarity-polynomial
  (doseq [gap [1.0e-12 1.0e-8 0.01]]
    (let [start [[-1.0 0.0 0.0] [1.0 0.0 0.0] [0.0 -1.0 gap] [0.0 1.0 gap]]
          end (mapv (fn [[x y z]] [z y (- x)]) start)
          result (contact/sweep! :edge-edge start end {:maximum-iterations 1})]
      ;; Common linear interpolation toward a quarter turn is invertible.
      ;; The edges stay separated, but no fixed axis separates both sweeps.
      (is (= :non-coplanarity (:certificate result)))
      (is (false? (:possible-contact? result)))))
  (let [start [[-1.0 0.0 0.0] [1.0 0.0 0.0] [0.0 -1.0 0.01] [0.0 1.0 0.01]]
        end (-> start (assoc-in [2 2] -0.01) (assoc-in [3 2] -0.01))
        result (contact/sweep! :edge-edge start end)]
    (is (:possible-contact? result))
    (is (nil? (:certificate result)))))
