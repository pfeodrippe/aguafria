(ns field-lab.geometry
  "Strict interval certificates shared by the native CCD and implicit solver adapters."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [field-lab.physics :as p]))

(az/defextern nextafter {:zig/prefix "pub extern"}
  :- :f64 [[value :f64] [direction :f64]])

(az/defextern fegetround {:zig/prefix "pub extern"} :- :c_int [])

(az/defstruct Interval [[:lower :f64] [:upper :f64]])

(az/defn down :- :f64 [[value :f64]]
  (nextafter value (- (math/inf :f64))))

(az/defn up :- :f64 [[value :f64]]
  (nextafter value (math/inf :f64)))

(az/defn point :- Interval [[value :f64]]
  (Interval {:lower value :upper value}))

(az/defn add :- Interval [[a Interval] [b Interval]]
  (Interval {:lower (down (+ (az/field a lower) (az/field b lower)))
             :upper (up (+ (az/field a upper) (az/field b upper)))}))

(az/defn subtract :- Interval [[a Interval] [b Interval]]
  (add a (Interval {:lower (- (az/field b upper)) :upper (- (az/field b lower))})))

(az/defn multiply :- Interval [[a Interval] [b Interval]]
  (let [aa (* (az/field a lower) (az/field b lower))
        ab (* (az/field a lower) (az/field b upper))
        ba (* (az/field a upper) (az/field b lower))
        bb (* (az/field a upper) (az/field b upper))]
    (Interval {:lower (down (ak/min aa ab ba bb)) :upper (up (ak/max aa ab ba bb))})))

(az/defn vector-subtract
  :- p/Vec3 [[a p/Vec3] [b p/Vec3]]
  (p/Vec3 {:x (- (az/field a x) (az/field b x))
            :y (- (az/field a y) (az/field b y))
            :z (- (az/field a z) (az/field b z))}))

(az/defn component :- :f64 [[vector p/Vec3] [axis :usize]]
  (if (ak/== axis 0) (az/field vector x) (if (ak/== axis 1) (az/field vector y) (az/field vector z))))

(az/defn difference :- [:array 3 Interval] [[a p/Vec3] [b p/Vec3]]
  (az/array-init [:array 3 Interval]
    [(subtract (point (az/field a x)) (point (az/field b x)))
     (subtract (point (az/field a y)) (point (az/field b y)))
     (subtract (point (az/field a z)) (point (az/field b z)))]))

(az/defn determinant
  :- Interval [[u [:array 3 Interval]] [v [:array 3 Interval]] [w [:array 3 Interval]]]
  (let [^:var result (point 0.0)]
    (dotimes [i 3]
      (let [j (ak/mod (+ i 1) 3) k (ak/mod (+ i 2) 3)]
        (set! result (add result (multiply (az/index u i)
                                  (subtract (multiply (az/index v j) (az/index w k))
                                            (multiply (az/index v k) (az/index w j))))))))
    result))

(az/defn sign :- :i32 [[value Interval]]
  (if (> (az/field value lower) 0) 1 (if (< (az/field value upper) 0) -1 0)))

(az/defn average :- Interval [[a Interval] [b Interval]]
  (multiply (add a b) (point 0.5)))

(az/defn cubic-excludes-zero?
  :- :bool [[coefficients [:array 4 Interval]] [depth :u32]]
  (let [^:var positive true ^:var negative true]
    (dotimes [index 4]
      (az/set-many! positive (and positive (> (az/field (az/index coefficients index) lower) 0))
                    negative (and negative (< (az/field (az/index coefficients index) upper) 0))))
    (when (or positive negative) (ak/return true)))
  (when (or (ak/== depth 0) (ak/== (sign (az/index coefficients 0)) 0)
             (ak/!= (sign (az/index coefficients 0)) (sign (az/index coefficients 3))))
    (ak/return false))
  (let [a (average (az/index coefficients 0) (az/index coefficients 1))
        b (average (az/index coefficients 1) (az/index coefficients 2))
        c (average (az/index coefficients 2) (az/index coefficients 3))
        d (average a b) e (average b c) midpoint (average d e)]
    (and (cubic-excludes-zero? (az/array-init [:array 4 Interval] [(az/index coefficients 0) a d midpoint]) (- depth 1))
         (cubic-excludes-zero? (az/array-init [:array 4 Interval] [midpoint e c (az/index coefficients 3)]) (- depth 1)))))

(az/defn non-coplanar?
  :- :bool [[before [:pointer {:size :c :const? true} p/Vec3]] [after [:pointer {:size :c :const? true} p/Vec3]]]
  (let [edge-u-before (difference (az/index before 1) (az/index before 0))
        edge-v-before (difference (az/index before 2) (az/index before 0))
        edge-w-before (difference (az/index before 3) (az/index before 0))
        edge-u-after (difference (az/index after 1) (az/index after 0))
        edge-v-after (difference (az/index after 2) (az/index after 0))
        edge-w-after (difference (az/index after 3) (az/index after 0))
        coefficients (az/array-init [:array 4 Interval]
                       [(multiply (determinant edge-u-before edge-v-before edge-w-before) (point 3.0))
                        (add (add (determinant edge-u-after edge-v-before edge-w-before) (determinant edge-u-before edge-v-after edge-w-before)) (determinant edge-u-before edge-v-before edge-w-after))
                        (add (add (determinant edge-u-after edge-v-after edge-w-before) (determinant edge-u-after edge-v-before edge-w-after)) (determinant edge-u-before edge-v-after edge-w-after))
                        (multiply (determinant edge-u-after edge-v-after edge-w-after) (point 3.0))])]
    ;; Three times the Bernstein coefficients avoids an inexact division by 3.
    (cubic-excludes-zero? coefficients 12)))

(az/defn projected-difference
  :- Interval [[a p/Vec3] [b p/Vec3] [axis p/Vec3]]
  (let [^:var result (point 0.0)]
    (dotimes [index 3]
      (let [factor (component axis index)]
        (when (ak/== factor 0.0) (ak/continue))
        (let [delta (- (component a index) (component b index))
              lower (down delta) upper (up delta)
              product (Interval {:lower (down (* factor (if (> factor 0) lower upper)))
                                  :upper (up (* factor (if (> factor 0) upper lower)))})]
          (set! result (add result product)))))
    result))

(az/defn finite-vector?
  :- :bool [[v p/Vec3]]
  (and (math/isFinite (az/field v x)) (math/isFinite (az/field v y)) (math/isFinite (az/field v z))))

(az/defn separating-axis?
  :- :bool
  [[kind :u32] [before [:pointer {:size :c :const? true} p/Vec3]]
   [after [:pointer {:size :c :const? true} p/Vec3]] [axis p/Vec3]]
  (when (or (ak/! (finite-vector? axis))
             (and (ak/== (az/field axis x) 0) (ak/== (az/field axis y) 0) (ak/== (az/field axis z) 0)))
    (ak/return false))
  (let [^:var positive true ^:var negative true
        count (if (ak/== kind 0) (ak/as :usize 1) 2)]
    (dotimes [time 2]
      (let [points (if (ak/== time 0) before after)]
        (dotimes [a count]
          (dotimes [right (- 4 count)]
            (let [bound (projected-difference (az/index points a) (az/index points (+ count right)) axis)]
              (az/set-many! positive (and positive (> (az/field bound lower) 0))
                            negative (and negative (< (az/field bound upper) 0))))))))
    (or positive negative)))

(az/defn separated?
  :- :bool
  [[kind :u32] [before [:pointer {:size :c :const? true} p/Vec3]] [after [:pointer {:size :c :const? true} p/Vec3]]]
  (dotimes [time 2]
    (let [points (if (ak/== time 0) before after)]
      (if (ak/== kind 0)
        (let [normal (p/cross (vector-subtract (az/index points 2) (az/index points 1))
                              (vector-subtract (az/index points 3) (az/index points 1)))]
          (when (separating-axis? kind before after normal) (ak/return true))
          (dotimes [edge 3]
            (let [a (+ 1 edge) b (+ 1 (ak/mod (+ edge 1) 3))
                  side (p/cross normal (vector-subtract (az/index points b) (az/index points a)))]
              (when (or (separating-axis? kind before after side)
                         (separating-axis? kind before after (vector-subtract (az/index points 0) (az/index points a))))
                (ak/return true)))))
        (let [u (vector-subtract (az/index points 1) (az/index points 0))
              v (vector-subtract (az/index points 3) (az/index points 2))
              offset (vector-subtract (az/index points 0) (az/index points 2))]
          (when (or (separating-axis? kind before after (p/cross u v))
                     (separating-axis? kind before after (p/cross u (p/cross u offset)))
                     (separating-axis? kind before after (p/cross v (p/cross v offset)))
                     (separating-axis? kind before after offset))
            (ak/return true))))))
  false)

(az/defn pitoco_geometry_ccd_prepare
  {:attrs #{:export}}
  :- :u32
  [[kind :u32] [before [:pointer {:size :c :const? true} p/Vec3]] [after [:pointer {:size :c :const? true} p/Vec3]]
   [separation :f64] [tolerance :f64] [maximum-time :f64] [maximum-iterations :u32] [error-bound [:c-pointer :f64]]]
  ;; 0 needs upstream CCD, 1/2 certified clear, 3 invalid input.
  (when (or (> kind 1) (ak/== before null) (ak/== after null) (ak/== error-bound null)
             (ak/! (math/isFinite separation)) (< separation 0)
             (ak/! (math/isFinite tolerance)) (<= tolerance 0)
             (ak/! (math/isFinite maximum-time)) (<= maximum-time 0) (> maximum-time 1)
             (ak/== maximum-iterations 0) (> maximum-iterations 1000000) (ak/!= (fegetround) 0))
    (ak/return 3))
  (let [^:var extent (az/array-init [:array 3 :f64] [1.0 1.0 1.0])]
    (dotimes [index 4]
      (dotimes [axis 3]
        (let [a (component (az/index before index) axis) b (component (az/index after index) axis)]
          (when (or (ak/! (math/isFinite a)) (ak/! (math/isFinite b)) (> (ak/abs a) 1.0e50) (> (ak/abs b) 1.0e50))
            (ak/return 3))
          (set! (az/index extent axis) (ak/max (az/index extent axis) (ak/abs a) (ak/abs b))))))
    (dotimes [axis 3] (when (>= separation (az/index extent axis)) (ak/return 3)))
    (when (ak/== separation 0.0)
      (when (separated? kind before after) (ak/return 1))
      (when (non-coplanar? before after) (ak/return 2)))
    (let [factor (if (> separation 0.0)
                   (if (ak/== kind 0) (ak/as :f64 7.549516567451064e-15) 7.105427357601002e-15)
                   (if (ak/== kind 0) (ak/as :f64 6.661338147750939e-15) 6.217248937900877e-15))]
      (dotimes [axis 3]
        (let [^:var bound factor]
          (dotimes [_ 3] (set! bound (up (* bound (az/index extent axis)))))
          (set! (az/index error-bound axis) bound))))
    0))

(az/defn column-point
  :- p/Vec3 [[data [:pointer {:size :c :const? true} :f64]] [index :usize] [nodes :usize]]
  (p/Vec3 {:x (az/index data index) :y (az/index data (+ nodes index)) :z (az/index data (+ (* 2 nodes) index))}))

(az/defn pitoco_geometry_positive_path
  {:attrs #{:export}}
  :- :u32
  [[nodes :u32] [tet-count :u32] [cells [:pointer {:size :c :const? true} :u32]]
   [floor [:pointer {:size :c :const? true} :u8]]
   [start [:pointer {:size :c :const? true} :f64]] [end [:pointer {:size :c :const? true} :f64]]]
  (when (or (ak/== start null) (ak/== end null) (and (> tet-count 0) (ak/== cells null))) (ak/return 0))
  (dotimes [tet tet-count]
    (let [^{:var [:array 4 p/Vec3]} before ak/undefined
          ^{:var [:array 4 p/Vec3]} after ak/undefined]
      (dotimes [j 4]
        (let [node (az/index cells (+ (* tet 4) j))]
          (when (>= node nodes) (ak/return 0))
          (az/set-many! (az/index before j) (column-point start node nodes)
                        (az/index after j) (column-point end node nodes))))
      (when (or (<= (p/dot (vector-subtract (az/index before 1) (az/index before 0))
                            (p/cross (vector-subtract (az/index before 2) (az/index before 0))
                                     (vector-subtract (az/index before 3) (az/index before 0)))) 0)
                 (ak/! (non-coplanar? (ak/& (az/index before 0)) (ak/& (az/index after 0)))))
        (ak/return 0))))
  (when (ak/!= floor null)
    (dotimes [node nodes]
      (when (and (ak/!= (az/index floor node) 0)
                  (or (<= (az/index start (+ nodes node)) 0) (<= (az/index end (+ nodes node)) 0)))
        (ak/return 0))))
  1)

(az/defn pitoco_geometry_trial
  {:attrs #{:export}}
  :- :void
  [[count :u32] [rest [:pointer {:size :c :const? true} :f64]]
   [start [:pointer {:size :c :const? true} :f64]] [direction [:pointer {:size :c :const? true} :f64]]
   [alpha :f64] [output [:c-pointer :f64]]]
  (dotimes [index count]
    (let [trial (+ (az/index start index) (* alpha (az/index direction index)))
          reference (az/index rest index)]
      (set! (az/index output index) (+ reference (- trial reference))))))

(az/defn pitoco_geometry_safe_fraction
  {:attrs #{:export}}
  :- :f64
  [[nodes :u32] [tet-count :u32] [cells [:pointer {:size :c :const? true} :u32]]
   [floor [:pointer {:size :c :const? true} :u8]] [rest [:pointer {:size :c :const? true} :f64]]
   [start [:pointer {:size :c :const? true} :f64]] [direction [:pointer {:size :c :const? true} :f64]]
   [surface-fraction :f64] [scratch [:c-pointer :f64]]]
  ;; The surface fraction comes from IPC. Ground clearance and positive-volume
  ;; backtracking are Pitoco's policy, and therefore live in AguaFria.
  (let [^:var alpha surface-fraction]
    (pitoco_geometry_trial (* 3 nodes) rest start direction 1.0 scratch)
    (when (ak/!= floor null)
      (dotimes [node nodes]
        (let [height (az/index start (+ nodes node)) target (az/index scratch (+ nodes node))]
          (when (and (ak/!= (az/index floor node) 0) (< target (* 0.2 height)))
            (set! alpha (ak/min alpha (/ (* 0.8 height) (- height target))))))))
    (dotimes [_ 64]
      (when (<= alpha 0.0) (ak/return 0.0))
      (pitoco_geometry_trial (* 3 nodes) rest start direction alpha scratch)
      (when (ak/!= (pitoco_geometry_positive_path nodes tet-count cells floor start scratch) 0)
        (ak/return alpha))
      (set! alpha (* alpha 0.5)))
    0.0))

(az/defn edge-less?
  :- :bool [[context :u8] [left :u64] [right :u64]]
  (set! _ context)
  (< left right))

(az/defn pitoco_geometry_edges
  {:attrs #{:export}}
  :- :u32
  [[nodes :u32] [face-count :u32] [faces [:pointer {:size :c :const? true} :u32]] [keys [:c-pointer :u64]]]
  ;; Caller supplies capacity for three edges per face. Sorting packed indices
  ;; produces the same lexicographic, unique edge order as the former C++ set.
  (when (or (ak/== faces null) (ak/== keys null) (> face-count 2000000)) (ak/return 0))
  (dotimes [face face-count]
    (dotimes [local 3]
      (let [a (az/index faces (+ (* face 3) local))
            b (az/index faces (+ (* face 3) (ak/mod (+ local 1) 3)))]
        (when (or (>= a nodes) (>= b nodes) (ak/== a b)) (ak/return 0))
        (set! (az/index keys (+ (* face 3) local))
              (ak/| (ak/<< (ak/as :u64 (ak/min a b)) 32) (ak/max a b))))))
  (mem/sortUnstable :u64 (az/slice keys 0 (* (ak/as :usize face-count) 3)) (ak/as :u8 0) edge-less?)
  (let [^{:var :u32} count 0]
    (dotimes [index (* (ak/as :usize face-count) 3)]
      (when (or (ak/== count 0) (ak/!= (az/index keys index) (az/index keys (- count 1))))
        (set! (az/index keys count) (az/index keys index))
        (set! count (+ count 1))))
    count))
