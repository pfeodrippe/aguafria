(ns field-lab.contact-mesh-test
  (:require [pitoco.geometry :as geometry]
            [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.contact-mesh :as contact]
            [field-lab.geometry :as certificate]
            [field-lab.nonlinear-job :as job]))

(deftest outward-rounding-covers-ieee-boundaries
  (let [values (for [exponent (range 2048)
                     fraction [0 1 4503599627370495]
                     sign [0 Long/MIN_VALUE]]
                 (Double/longBitsToDouble (bit-or sign (bit-shift-left exponent 52) fraction)))
        failures
        (vec (take 10
                   (for [value values
                         [direction native reference] [[:up certificate/up #(Math/nextUp (double %))]
                                                       [:down certificate/down #(Math/nextDown (double %))]]
                         :let [expected (reference value)
                               actual (double (native value))]
                         :when (if (Double/isNaN expected)
                                 (not (Double/isNaN actual))
                                 (not= (Double/doubleToRawLongBits expected) (Double/doubleToRawLongBits actual)))]
                     {:direction direction :input value :expected expected :actual actual})))]
    (is (empty? failures) (pr-str failures))
    (spit "build/outward-rounding-evidence.edn"
          (pr-str {:values (* 2048 3 2) :directions 2 :mismatches failures
                   :reference :java-math-next-up-down}))))

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


(az/defn hierarchy-node contact/TreeNode
  [[surface [:* contact/Surface]] [index :usize]]
  (az/index (az/field surface tree) index))

(az/defn hierarchy-leaf :usize
  [[surface [:* contact/Surface]] [index :usize]]
  (az/index (az/field surface leaves) index))

(az/defn adjacency-offset :usize
  [[surface [:* contact/Surface]] [index :usize]]
  (az/index (az/field surface offsets) index))

(az/defn adjacency-face :u32
  [[surface [:* contact/Surface]] [index :usize]]
  (az/index (az/field surface incidents) index))

(defn native-surface! [points faces]
  (let [surface (contact/create! (count points) (count faces))]
    (try
      (doseq [[index point] (map-indexed vector points)]
        (contact/set-point! surface index (job/vector-map point)))
      (doseq [[index face] (map-indexed vector faces)]
        (apply contact/set-face! surface index face))
      (assert (contact/build-hierarchy! surface))
      surface
      (catch Throwable error
        (contact/destroy! surface)
        (throw error)))))

(defn hierarchy-snapshot [surface face-count]
  (mapv #(az/value (hierarchy-node surface %)) (range (dec (* 2 face-count)))))

(defn inspect-hierarchy [surface points faces]
  (let [tree (hierarchy-snapshot surface (count faces))
        visit (fn visit [index parent depth]
                (let [{:keys [leaf face left right bounds] actual-parent :parent} (tree index)
                      lower (mapv (:lower bounds) [:x :y :z])
                      upper (mapv (:upper bounds) [:x :y :z])
                      children (when-not leaf [(visit left index (inc depth))
                                               (visit right index (inc depth))])
                      indices (if leaf [face] (vec (mapcat :faces children)))
                      expected (mapcat #(map points (faces %)) indices)]
                  {:valid? (and (= parent actual-parent)
                                (or leaf (and (> left index) (> right index) (not= left right)))
                                (every? :valid? children)
                                (every? #(every? true? (map <= lower % upper)) expected))
                   :faces indices
                   :depth (reduce max depth (map :depth children))}))
        report (visit 0 0 0)
        adjacency
        (mapv (fn [vertex]
                (let [start (adjacency-offset surface vertex)
                      end (adjacency-offset surface (inc vertex))]
                  (mapv #(adjacency-face surface %) (range start end))))
              (range (count points)))
        expected-adjacency
        (mapv (fn [vertex]
                (vec (for [[i face] (map-indexed vector faces)
                           member face :when (= member vertex)] i)))
              (range (count points)))]
    (assoc report :valid? (and (:valid? report)
                              (= (vec (range (count faces))) (vec (sort (:faces report))))
                              (= adjacency expected-adjacency)
                              (every? (fn [face]
                                        (let [node (tree (hierarchy-leaf surface face))]
                                          (and (:leaf node) (= face (:face node)))))
                                      (range (count faces)))))))

(deftest native-sah-topology-and-adjacency
  (doseq [{:keys [points faces]} [cube concave
                                {:points [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]]
                                 :faces (vec (repeat 257 [0 1 2]))}
                                {:points [[0.0 0.0 0.0]] :faces [[0 0 0]]}]]
    (let [surface (native-surface! points faces)]
      (try
        (let [report (inspect-hierarchy surface points faces)
              before (hierarchy-snapshot surface (count faces))]
          (is (:valid? report))
          (is (<= (:depth report) 49))
          (is (contact/build-hierarchy! surface))
          (is (= before (hierarchy-snapshot surface (count faces))) "Deterministic rebuild"))
        (finally (contact/destroy! surface))))))

(deftest native-sah-matches-existing-queries-and-refit
  (let [random (java.util.Random. 127483)
        queries (vec (repeatedly 200 #(vec (repeatedly 3 (fn [] (- (* 4.0 (.nextDouble random)) 1.0))))))]
    (doseq [{:keys [points faces]} [cube concave]]
      (let [baseline (contact/build! points faces)
            native (native-surface! points faces)]
        (try
          (let [differences
                (for [point queries
                      :let [old (query baseline point) new (query native point)]
                      :when (or (> (abs (- (:squared-distance old) (:squared-distance new))) 1e-12)
                                (> (abs (- (:signed-distance old) (:signed-distance new))) 1e-12))]
                  {:query point :old old :native new})]
            (is (empty? differences) (pr-str (take 3 differences))))
          (let [moved [0.1 -0.2 0.05]
                changed (assoc points 0 moved)]
            (contact/move-point! native 0 (job/vector-map moved))
            (is (:valid? (inspect-hierarchy native changed faces)))
            (let [before (hierarchy-snapshot native (count faces))]
              (contact/refit! native)
              (is (= before (hierarchy-snapshot native (count faces)))
                  "Incremental adjacency updates equal complete bottom-up refit")))
          (finally
            (contact/destroy! baseline)
            (contact/destroy! native)))))))

(deftest native-hierarchy-rejects-input-before-topology-writes
  (let [{:keys [points faces]} cube
        surface (native-surface! points faces)]
    (try
      (let [before (hierarchy-snapshot surface (count faces))]
        (doseq [value [Double/NaN Double/POSITIVE_INFINITY 1.0e51]]
          (contact/set-point! surface 0 (job/vector-map [value 0.0 0.0]))
          (is (false? (contact/build-hierarchy! surface)))
          (is (= before (hierarchy-snapshot surface (count faces)))))
        (contact/set-point! surface 0 (job/vector-map (points 0)))
        (contact/set-face! surface 0 (count points) 1 2)
        (is (false? (contact/build-hierarchy! surface)))
        (is (= before (hierarchy-snapshot surface (count faces)))))
      (finally (contact/destroy! surface)))))

(az/defn exercise-hierarchy-capacity! :usize
  "Coincident centroids force the bounded fallback at the maximum face capacity."
  [[face-count :usize]]
  (let [surface (contact/create! 3 face-count)
        ^{:var :usize} maximum-depth 0
        ^{:var :usize} leaves 0]
    (defer (contact/destroy! surface))
    (contact/set-point! surface 0 (p/v 0.0 0.0 0.0))
    (contact/set-point! surface 1 (p/v 1.0 0.0 0.0))
    (contact/set-point! surface 2 (p/v 0.0 1.0 0.0))
    (dotimes [face face-count] (contact/set-face! surface face 0 1 2))
    (when (ak/! (contact/build-hierarchy! surface)) (ak/return 0))
    (dotimes [index (- (* 2 face-count) 1)]
      (let [node (hierarchy-node surface index)
            ^{:var :usize} parent index
            ^{:var :usize} depth 0]
        (while (ak/!= parent 0)
          (az/set-many!
            parent (az/field (hierarchy-node surface parent) parent)
            depth (+ depth 1))
          (when (> depth 49) (ak/return 0)))
        (set! maximum-depth (ak/max maximum-depth depth))
        (if (az/field node leaf)
          (do
            (when (ak/!= (hierarchy-leaf surface (az/field node face)) index) (ak/return 0))
            (ak/+= leaves 1))
          (when (or (<= (az/field node left) index) (<= (az/field node right) index)
                    (ak/!= (az/field (hierarchy-node surface (az/field node left)) parent) index)
                    (ak/!= (az/field (hierarchy-node surface (az/field node right)) parent) index))
            (ak/return 0)))))
    (when (or (ak/!= leaves face-count) (ak/!= (adjacency-offset surface 3) (* 3 face-count)))
      (ak/return 0))
    maximum-depth))

(deftest native-hierarchy-capacity-is-bounded
  (is (= 17 (exercise-hierarchy-capacity! 80000))))


(deftest adversarial-sah-depth-fits-existing-query-stack
  (let [points (mapv #(vector (Math/scalb 1.0 (int (- %))) 0.0 0.0) (range 1000))
        faces (mapv #(vector % % %) (range 1000))
        surface (native-surface! points faces)]
    (try
      (let [report (inspect-hierarchy surface points faces)]
        (is (:valid? report))
        (is (= 42 (:depth report)))
        (is (< (:depth report) 64)))
      (finally (contact/destroy! surface)))))

(az/defn packed-fixture [:array 1024 :u32]
  [[surface [:* contact/Surface]] [capacity :usize]]
  (let [^{:var [:array 1024 :u32]} words ak/undefined]
    (dotimes [i 1024] (set! (az/index words i) 0xcafebabe))
    (set! (az/index words 1023)
          (ak/intCast (contact/pack-hierarchy! surface (ak/& (az/index words 0)) capacity)))
    words))

(defn word-float [word]
  (double (Float/intBitsToFloat (unchecked-int word))))

(deftest gpu-packet-bounds-layout-and-rejection
  (let [surface (native-surface! (:points cube) (:faces cube))
        required (contact/packed-hierarchy-words surface)]
    (try
      (let [packet (vec (az/value (packed-fixture surface required)))
            [nodes points faces] packet
            point-start (+ 4 (* 8 nodes))
            face-start (+ point-start (* 4 points))]
        (is (= required (packet 1023)))
        (is (= [23 8 12] [nodes points faces]))
        (is (every? #{0xcafebabe} (subvec packet required 1023)))
        (is (= (:points cube)
               (mapv (fn [i] (mapv #(word-float (packet (+ point-start (* 4 i) %))) (range 3)))
                     (range points))))
        (is (= (:faces cube)
               (mapv (fn [i] (subvec packet (+ face-start (* 4 i)) (+ face-start (* 4 i) 3)))
                     (range faces))))
        (doseq [i (range nodes)]
          (let [node (az/value (hierarchy-node surface i))
                start (+ 4 (* 8 i))]
            (doseq [[axis coordinate] (map-indexed vector [:x :y :z])]
              (is (< (word-float (packet (+ start axis))) (get-in node [:bounds :lower coordinate])))
              (is (> (word-float (packet (+ start 4 axis))) (get-in node [:bounds :upper coordinate])))))))
      (doseq [capacity [0 (dec required)]]
        (let [packet (vec (az/value (packed-fixture surface capacity)))]
          (is (zero? (packet 1023)))
          (is (every? #{0xcafebabe} (subvec packet 0 1023)))))
      (contact/set-point! surface 0 (p/v Double/NaN 0.0 0.0))
      (let [packet (vec (az/value (packed-fixture surface required)))]
        (is (zero? (packet 1023)))
        (is (every? #{0xcafebabe} (subvec packet 0 1023))))
      (finally (contact/destroy! surface)))))
