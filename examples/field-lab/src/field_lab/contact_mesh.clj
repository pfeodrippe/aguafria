(ns field-lab.contact-mesh
  "Refittable triangle hierarchy and signed closest-feature queries.
  Sign requires a closed, consistently oriented, non-self-intersecting surface."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.fem :as fem]
            [field-lab.physics :as p]
            [field-lab.fem-job :as linear]
            [field-lab.nonlinear-job :as job]))

(az/defstruct Box
  [[:lower p/Vec3] [:upper p/Vec3]])

(az/defstruct TreeNode
  [[:bounds Box] [:parent :usize] [:left :usize] [:right :usize]
   [:face :usize] [:leaf :bool]])

(az/defstruct Surface
  [[:points [:slice p/Vec3]] [:faces [:slice [:array 3 :u32]]]
   [:tree [:slice TreeNode]] [:leaves [:slice :usize]]
   [:offsets [:slice :usize]] [:incidents [:slice :u32]]])

(az/defstruct Closest {:layout :extern}
  [[:point p/Vec3] [:weights p/Vec3] [:squared-distance :f64]
   [:face :u32] [:signed-distance :f64] [:normal p/Vec3]])

(az/defn create!
  :- [:* Surface]
  [[nodes :usize] [faces :usize]]
  (debug/assert (and (> nodes 0) (> faces 0) (<= nodes 20000) (<= faces 80000)))
  (let [surface (catch ((az/field heap/page_allocator create) Surface)
                  (debug/panic "Unable to allocate contact mesh" []))]
    (set! (az/deref surface)
          (Surface {:points (fem/allocate p/Vec3 nodes)
                    :faces (fem/allocate (az/type [:array 3 :u32]) faces)
                    :tree (fem/allocate TreeNode (- (* 2 faces) 1))
                    :leaves (fem/allocate :usize faces)
                    :offsets (fem/allocate :usize (+ nodes 1))
                    :incidents (fem/allocate :u32 (* 3 faces))}))
    surface))

(az/defn destroy!
  :- :void
  [[surface [:* Surface]]]
  ((az/field heap/page_allocator free) (az/field surface points))
  ((az/field heap/page_allocator free) (az/field surface faces))
  ((az/field heap/page_allocator free) (az/field surface tree))
  ((az/field heap/page_allocator free) (az/field surface leaves))
  ((az/field heap/page_allocator free) (az/field surface offsets))
  ((az/field heap/page_allocator free) (az/field surface incidents))
  ((az/field heap/page_allocator destroy) surface))

(az/defn set-point!
  "Initialization/batched refit setter; call refit! before queries."
  :- :void
  [[surface [:* Surface]] [node :usize] [point p/Vec3]]
  (set! (az/index (az/field surface points) node) point))

(az/defn set-face!
  :- :void
  [[surface [:* Surface]] [index :usize] [a :u32] [b :u32] [c :u32]]
  (set! (az/index (az/field surface faces) index) (az/array-init [:array 3 :u32] [a b c])))

(az/defn set-tree!
  :- :void
  [[surface [:* Surface]] [index :usize] [parent :usize]
   [left :usize] [right :usize] [face :usize] [leaf :bool]]
  (set! (az/index (az/field surface tree) index)
        (TreeNode {:parent parent :left left :right right :face face :leaf leaf
                    :bounds (Box {:lower (p/v 0.0 0.0 0.0) :upper (p/v 0.0 0.0 0.0)})}))
  (when leaf (set! (az/index (az/field surface leaves) face) index)))

(az/defn set-offset!
  :- :void
  [[surface [:* Surface]] [index :usize] [value :usize]]
  (set! (az/index (az/field surface offsets) index) value))

(az/defn set-incident!
  :- :void
  [[surface [:* Surface]] [index :usize] [face :u32]]
  (set! (az/index (az/field surface incidents) index) face))

(az/defn min-vector
  :- p/Vec3
  [[a p/Vec3] [b p/Vec3]]
  (p/v (ak/min (az/field a x) (az/field b x))
       (ak/min (az/field a y) (az/field b y))
       (ak/min (az/field a z) (az/field b z))))

(az/defn max-vector
  :- p/Vec3
  [[a p/Vec3] [b p/Vec3]]
  (p/v (ak/max (az/field a x) (az/field b x))
       (ak/max (az/field a y) (az/field b y))
       (ak/max (az/field a z) (az/field b z))))

(az/defn refit-node!
  :- :void
  [[surface [:* Surface]] [index :usize]]
  (let [node (az/index (az/field surface tree) index)]
    (if (az/field node leaf)
      (let [face (az/index (az/field surface faces) (az/field node face))
            a (az/index (az/field surface points) (az/index face 0))
            b (az/index (az/field surface points) (az/index face 1))
            c (az/index (az/field surface points) (az/index face 2))]
        (set! (az/field (az/index (az/field surface tree) index) bounds)
              (Box {:lower (min-vector a (min-vector b c)) :upper (max-vector a (max-vector b c))})))
      (let [a (az/field (az/index (az/field surface tree) (az/field node left)) bounds)
            b (az/field (az/index (az/field surface tree) (az/field node right)) bounds)]
        (set! (az/field (az/index (az/field surface tree) index) bounds)
              (Box {:lower (min-vector (az/field a lower) (az/field b lower))
                    :upper (max-vector (az/field a upper) (az/field b upper))}))))))

(az/defn refit!
  :- :void
  [[surface [:* Surface]]]
  (let [size (az/field (az/field surface tree) len)]
    (dotimes [index size]
      (refit-node! surface (- (- size 1) index)))))

(az/defn move-point!
  "Update incident leaves and ancestors after a contact correction."
  :- :void
  [[surface [:* Surface]] [vertex :usize] [point p/Vec3]]
  (set-point! surface vertex point)
  (let [start (az/index (az/field surface offsets) vertex)
        end (az/index (az/field surface offsets) (+ vertex 1))]
    (dotimes [offset (- end start)]
      (let [face (az/index (az/field surface incidents) (+ start offset))
            ^{:var :usize} index (az/index (az/field surface leaves) face)]
        (while true
          (refit-node! surface index)
          (when (ak/== index 0) (ak/break))
          (set! index (az/field (az/index (az/field surface tree) index) parent)))))))

(az/defn box-distance
  :- :f64
  [[bounds Box] [point p/Vec3]]
  (let [sum (max-vector (p/v 0.0 0.0 0.0)
                        (max-vector (p/add (az/field bounds lower) (p/scale point -1.0))
                                    (p/add point (p/scale (az/field bounds upper) -1.0))))]
    (p/dot sum sum)))

(az/defn bounds-contain?
  "Broad-phase test against the root box, not a solid-interior classification."
  :- :bool
  [[surface [:* Surface]] [point p/Vec3]]
  (<= (box-distance (az/field (az/index (az/field surface tree) 0) bounds) point) 1.0e-24))

(az/defn unit-normal
  :- p/Vec3
  [[surface [:* Surface]] [index :usize]]
  (let [face (az/index (az/field surface faces) index)
        a (az/index (az/field surface points) (az/index face 0))
        b (az/index (az/field surface points) (az/index face 1))
        c (az/index (az/field surface points) (az/index face 2))
        normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))]
    (p/scale normal (/ 1.0 (ak/max 1.0e-30 (p/length normal))))))

(az/defn triangle-closest
  "Interior projection plus all three closed segments covers every Voronoi region."
  :- Closest
  [[point p/Vec3] [a p/Vec3] [b p/Vec3] [c p/Vec3]]
  (let [vertices (az/array-init [:array 3 p/Vec3] [a b c])
        ^:var result (Closest {:point a :weights (p/v 1.0 0.0 0.0)
                              :squared-distance 1.0e300 :face 0 :signed-distance 0.0
                              :normal (p/v 0.0 0.0 0.0)})]
    (dotimes [edge 3]
      (let [next (mod (+ edge 1) 3)
            start (az/index vertices edge)
            delta (p/add (az/index vertices next) (p/scale start -1.0))
            t (ak/max 0.0 (ak/min 1.0 (/ (p/dot (p/add point (p/scale start -1.0)) delta)
                                         (ak/max 1.0e-30 (p/dot delta delta)))))
            candidate (p/add start (p/scale delta t))
            difference (p/add point (p/scale candidate -1.0))
            squared (p/dot difference difference)]
        (when (< squared (az/field result squared-distance))
          (az/set-many!
            (az/field result point) candidate
            (az/field result squared-distance) squared
            (az/field result weights)
            (p/v (+ (if (ak/== edge 0) (- 1.0 t) 0.0) (if (ak/== next 0) t 0.0))
                 (+ (if (ak/== edge 1) (- 1.0 t) 0.0) (if (ak/== next 1) t 0.0))
                 (+ (if (ak/== edge 2) (- 1.0 t) 0.0) (if (ak/== next 2) t 0.0)))))))
    (let [ab (p/add b (p/scale a -1.0))
          ac (p/add c (p/scale a -1.0))
          ap (p/add point (p/scale a -1.0))
          d00 (p/dot ab ab)
          d01 (p/dot ab ac)
          d11 (p/dot ac ac)
          determinant (- (* d00 d11) (* d01 d01))]
      (when (> determinant 1.0e-30)
        (let [v (/ (- (* d11 (p/dot ap ab)) (* d01 (p/dot ap ac))) determinant)
              w (/ (- (* d00 (p/dot ap ac)) (* d01 (p/dot ap ab))) determinant)
              u (- 1.0 v w)
              candidate (p/add a (p/add (p/scale ab v) (p/scale ac w)))
              difference (p/add point (p/scale candidate -1.0))
              squared (p/dot difference difference)]
          (when (and (>= u 0.0) (>= v 0.0) (>= w 0.0) (< squared (az/field result squared-distance)))
            (az/set-many!
              (az/field result point) candidate
              (az/field result weights) (p/v u v w)
              (az/field result squared-distance) squared)))))
    result))

(az/defn pseudo-normal
  "Bærentzen–Aanæs: incident-angle weights at vertices; both normals at edges."
  :- p/Vec3
  [[surface [:* Surface]] [closest Closest]]
  (let [face (az/index (az/field surface faces) (az/field closest face))
        weights (az/field closest weights)
        ^{:var :usize} positives 0
        ^{:var :usize} first 0
        ^{:var :usize} second 0]
    (dotimes [local 3]
      (when (> (fem/component weights local) 0.0)
        (if (ak/== positives 0) (set! first (az/index face local)) (set! second (az/index face local)))
        (ak/+= positives 1)))
    (when (ak/== positives 3) (ak/return (unit-normal surface (az/field closest face))))
    (let [start (az/index (az/field surface offsets) first)
          end (az/index (az/field surface offsets) (+ first 1))
          point (az/index (az/field surface points) first)
          ^:var result (p/v 0.0 0.0 0.0)]
      (dotimes [offset (- end start)]
        (let [index (az/index (az/field surface incidents) (+ start offset))
              incident (az/index (az/field surface faces) index)
              normal (unit-normal surface index)]
          (if (ak/== positives 2)
            (when (or (ak/== second (az/index incident 0)) (ak/== second (az/index incident 1))
                      (ak/== second (az/index incident 2)))
              (set! result (p/add result normal)))
            (dotimes [local 3]
              (when (ak/== first (az/index incident local))
                (let [a (p/add (az/index (az/field surface points) (az/index incident (mod (+ local 1) 3)))
                               (p/scale point -1.0))
                      b (p/add (az/index (az/field surface points) (az/index incident (mod (+ local 2) 3)))
                               (p/scale point -1.0))
                      angle (math/atan2 (p/length (p/cross a b)) (p/dot a b))]
                  (set! result (p/add result (p/scale normal angle)))))))))
      (p/scale result (/ 1.0 (ak/max 1.0e-30 (p/length result)))))))

(az/defn closest-point
  :- Closest
  [[surface [:* Surface]] [point p/Vec3]]
  (let [^{:var [:array 64 :usize]} stack ak/undefined
        ^{:var :usize} size 1
        ^:var result (Closest {:point point :weights (p/v 0.0 0.0 0.0)
                              :squared-distance 1.0e300 :face 0 :signed-distance 0.0
                              :normal (p/v 0.0 0.0 0.0)})]
    (set! (az/index stack 0) 0)
    (while (> size 0)
      (ak/-= size 1)
      (let [node (az/index (az/field surface tree) (az/index stack size))]
        (when (<= (box-distance (az/field node bounds) point) (az/field result squared-distance))
          (if (az/field node leaf)
            (let [face (az/index (az/field surface faces) (az/field node face))
                  candidate (triangle-closest point
                                               (az/index (az/field surface points) (az/index face 0))
                                               (az/index (az/field surface points) (az/index face 1))
                                               (az/index (az/field surface points) (az/index face 2)))]
              (when (< (az/field candidate squared-distance) (az/field result squared-distance))
                (az/set-many!
                  result candidate
                  (az/field result face) (ak/intCast (az/field node face)))))
            (let [left (az/field node left)
                  right (az/field node right)
                  dl (box-distance (az/field (az/index (az/field surface tree) left) bounds) point)
                  dr (box-distance (az/field (az/index (az/field surface tree) right) bounds) point)]
              (debug/assert (<= (+ size 2) 64))
              ;; Push the farther child first; nearest traversal tightens pruning.
              (az/set-many!
                (az/index stack size) (if (< dl dr) right left)
                (az/index stack (+ size 1)) (if (< dl dr) left right)
                size (+ size 2)))))))
    (let [pseudo (pseudo-normal surface result)
          delta (p/add point (p/scale (az/field result point) -1.0))
          distance (ak/sqrt (az/field result squared-distance))
          inside (< (p/dot pseudo delta) 0.0)]
      (az/set-many!
        (az/field result signed-distance) (if inside (- distance) distance)
        (az/field result normal) (if (> distance 1.0e-15)
                                  (p/scale delta (if inside (/ -1.0 distance) (/ 1.0 distance))) pseudo)))
    result))

(defn hierarchy [points faces]
  (let [tree (atom [])
        centroid (fn [face] (linear/scale (/ 1.0 3.0) (apply mapv + (map points (faces face)))))]
    (letfn [(build [indices parent]
              (let [index (count @tree)]
                (swap! tree conj nil)
                (if (= 1 (count indices))
                  (swap! tree assoc index {:parent parent :left 0 :right 0 :face (first indices) :leaf true})
                  (let [centers (mapv centroid indices)
                        spans (mapv (fn [axis] (- (apply max (map #(nth % axis) centers))
                                                 (apply min (map #(nth % axis) centers)))) (range 3))
                        axis (apply max-key spans (range 3))
                        ordered (vec (sort-by #(nth (centroid %) axis) indices))
                        middle (quot (count indices) 2)
                        left (build (subvec ordered 0 middle) index)
                        right (build (subvec ordered middle) index)]
                    (swap! tree assoc index {:parent parent :left left :right right :face 0 :leaf false})))
                index))]
      (build (vec (range (count faces))) 0)
      @tree)))

(defn validate!
  "Require finite triangles on a closed, oriented vertex-manifold boundary.
  Unused volume nodes are allowed. This does not detect self-intersections."
  [points faces]
  (when-not (and (vector? points) (vector? faces)
                 (<= 1 (count points) 20000) (<= 1 (count faces) 80000)
                 (every? #(and (= 3 (count %))
                                (every? (fn [x] (and (number? x) (Double/isFinite (double x)))) %)) points)
                 (every? #(and (= 3 (count %)) (= 3 (count (set %)))
                                (every? (fn [i] (and (integer? i) (<= 0 i) (< i (count points)))) %)) faces))
    (throw (ex-info "Invalid contact surface topology" {})))
  (let [edges (group-by #(vec (sort (take 2 %)))
                        (mapcat (fn [[index [a b c]]]
                                  [[a b index] [b c index] [c a index]])
                                (map-indexed vector faces)))
        incident (reduce (fn [result [index face]]
                           (reduce #(update %1 %2 (fnil conj #{}) index) result face))
                         {} (map-indexed vector faces))
        neighbors (reduce (fn [result entries]
                            (when-not (and (= 2 (count entries))
                                           (= (take 2 (first entries)) (reverse (take 2 (second entries)))))
                              (throw (ex-info "Contact surface must have two oppositely oriented faces per edge"
                                              {:edge (first entries)})))
                            (let [[[_ _ a] [_ _ b]] entries]
                              (-> result (update a conj b) (update b conj a))))
                          (vec (repeat (count faces) #{})) (vals edges))
        connected (fn [allowed start]
                    (loop [pending [start] seen #{}]
                      (if-let [index (peek pending)]
                        (if (seen index)
                          (recur (pop pending) seen)
                          (recur (into (pop pending) (filter allowed (neighbors index))) (conj seen index)))
                        seen)))]
    (doseq [[index [a b c]] (map-indexed vector faces)]
      (let [ab (linear/subtract (points b) (points a))
            ac (linear/subtract (points c) (points a))
            normal (linear/cross ab ac)
            area-squared (linear/dot normal normal)]
        (when-not (and (Double/isFinite area-squared) (> area-squared 1.0e-30))
          (throw (ex-info "Degenerate contact triangle" {:face index})))))
    (doseq [[vertex adjacent] incident]
      (when-not (= adjacent (connected adjacent (first adjacent)))
        (throw (ex-info "Contact surface has a non-manifold vertex" {:vertex vertex}))))
    ;; Reject inward components. Translate the volume sum near the component
    ;; before accumulation to avoid cancellation far from the coordinate origin.
    (loop [remaining (set (range (count faces)))]
      (when (seq remaining)
        (let [component (connected remaining (first remaining))
              origin (points (first (faces (first component))))
              volume-six (reduce + (map (fn [index]
                                         (let [[a b c] (map #(linear/subtract (points %) origin) (faces index))]
                                           (linear/dot a (linear/cross b c)))) component))]
          (when-not (and (Double/isFinite volume-six) (pos? volume-six))
            (throw (ex-info "Contact boundary component must enclose positive outward-oriented volume" {})))
          (recur (reduce disj remaining component))))))
  true)

(defn build!
  "Build a private contact surface; caller owns and must destroy the result."
  [points faces]
  (validate! points faces)
  (az/await! 'field-lab.contact-mesh)
  (let [surface (create! (count points) (count faces))
        incidents (reduce (fn [result [index face]]
                            (reduce #(update %1 %2 conj index) result face))
                          (vec (repeat (count points) [])) (map-indexed vector faces))]
    (try
      (doseq [[index point] (map-indexed vector points)] (set-point! surface index (job/vector-map point)))
      (doseq [[index [a b c]] (map-indexed vector faces)] (set-face! surface index a b c))
      (doseq [[index {:keys [parent left right face leaf]}] (map-indexed vector (hierarchy points faces))]
        (set-tree! surface index parent left right face leaf))
      (doseq [[index offset] (map-indexed vector (reductions + 0 (map count incidents)))]
        (set-offset! surface index offset))
      (doseq [[index face] (map-indexed vector (mapcat identity incidents))]
        (set-incident! surface index face))
      (refit! surface)
      surface
      (catch Throwable error
        (destroy! surface)
        (throw error)))))
