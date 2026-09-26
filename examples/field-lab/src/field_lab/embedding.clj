(ns field-lab.embedding
  "Owned render geometry driven by a tetrahedral cache, independent of the solver.
  James (2020), Phong Deformation, equations 14 and 24–27; epsilon = 1."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as matrix]
            [field-lab.mesh-cache :as cache]))

(az/defstruct Binding {:layout :extern}
  [[:cell :u32] [:weights [:array 4 :f64]] [:valid :bool]])

(az/defstruct Render
  [[:source [:* cache/Cache]]
   [:reference [:slice p/Vec3]] [:bindings [:slice Binding]]
   [:faces [:slice [:array 3 :u32]]] [:positions [:slice p/Vec3]]
   [:normals [:slice p/Vec3]] [:inverse-bases [:slice matrix/Matrix]]
   [:cell-weights [:slice [:array 4 :f64]]] [:gradients [:slice matrix/Matrix]]
   [:source-nodes :usize] [:source-cells :usize] [:ready :bool]
   [:minimum-height :f64] [:maximum-correction :f64] [:reference-inset :f64]])

(az/defn transpose matrix/Matrix
  [[value matrix/Matrix]]
  (matrix/Matrix
   {:c0 (p/v (az/field (az/field value c0) x) (az/field (az/field value c1) x) (az/field (az/field value c2) x))
    :c1 (p/v (az/field (az/field value c0) y) (az/field (az/field value c1) y) (az/field (az/field value c2) y))
    :c2 (p/v (az/field (az/field value c0) z) (az/field (az/field value c1) z) (az/field (az/field value c2) z))}))

(az/defn inverse matrix/Matrix
  [[value matrix/Matrix]]
  (matrix/scale (transpose (matrix/cofactor value)) (/ 1.0 (matrix/determinant value))))

(az/defn basis matrix/Matrix
  [[a p/Vec3] [b p/Vec3] [c p/Vec3] [d p/Vec3]]
  (matrix/Matrix {:c0 (p/add b (p/scale a -1.0))
                  :c1 (p/add c (p/scale a -1.0))
                  :c2 (p/add d (p/scale a -1.0))}))

(az/defn multiply matrix/Matrix
  [[a matrix/Matrix] [b matrix/Matrix]]
  (matrix/Matrix {:c0 (matrix/apply-vector a (az/field b c0))
                  :c1 (matrix/apply-vector a (az/field b c1))
                  :c2 (matrix/apply-vector a (az/field b c2))}))

(az/defn centroid-offset p/Vec3
  [[source [:* cache/Cache]] [cell [:array 4 :u32]] [local :usize]]
  (let [origin (az/index (az/field source reference) (az/index cell local))
        ^:var offset (p/v 0.0 0.0 0.0)]
    (dotimes [corner 4]
      (set! offset (p/add offset (p/add (az/index (az/field source reference) (az/index cell corner))
                                       (p/scale origin -1.0)))))
    (p/scale offset 0.25)))

(az/defn create! [:* Render]
  "Precompute the regularized cell-to-vertex reconstruction in material space.
  The source must be a validated, nondegenerate tetrahedral cache."
  [[source [:* cache/Cache]] [points :usize] [faces :usize]]
  (debug/assert (and (> points 0) (<= points 80000) (> faces 0) (<= faces 80000)))
  (let [nodes (az/field (az/field source reference) len)
        cells (az/field (az/field source cells) len)
        result (catch ((az/field heap/page_allocator create) Render)
                 (debug/panic "Unable to allocate render embedding" []))
        systems (fem/allocate matrix/Matrix nodes)
        rhs (fem/allocate p/Vec3 nodes)
        sums (fem/allocate :f64 nodes)]
    (defer ((az/field heap/page_allocator free) systems))
    (defer ((az/field heap/page_allocator free) rhs))
    (defer ((az/field heap/page_allocator free) sums))
    (set! (az/deref result)
          (Render {:source source :reference (fem/allocate p/Vec3 points)
                   :bindings (fem/allocate Binding points)
                   :faces (fem/allocate (az/type [:array 3 :u32]) faces)
                   :positions (fem/allocate p/Vec3 points)
                   :normals (fem/allocate p/Vec3 points)
                   :inverse-bases (fem/allocate matrix/Matrix cells)
                   :cell-weights (fem/allocate (az/type [:array 4 :f64]) cells)
                   :gradients (fem/allocate matrix/Matrix nodes)
                   :source-nodes nodes :source-cells cells :ready false
                   :minimum-height 0.0 :maximum-correction 0.0 :reference-inset 0.0}))
    (dotimes [point points]
      (set! (az/index (az/field result bindings) point)
            (Binding {:cell 0 :weights (az/init [0.0 0.0 0.0 0.0] [:array 4 :f64]) :valid false})))
    (dotimes [face faces]
      (set! (az/index (az/field result faces) face) (az/init [0 0 0] [:array 3 :u32])))
    ;; A + I is positive definite even at vertices with one adjacent cell.
    (dotimes [node nodes]
      (az/set-many!
        (az/index systems node) (matrix/identity)
        (az/index rhs node) (p/v 0.0 0.0 0.0)
        (az/index sums node) 0.0))
    (dotimes [index cells]
      (let [cell (az/index (az/field source cells) index)
            rest (az/field source reference)
            edges (basis (az/index rest (az/index cell 0)) (az/index rest (az/index cell 1))
                         (az/index rest (az/index cell 2)) (az/index rest (az/index cell 3)))]
        (debug/assert (ak/!= (matrix/determinant edges) 0.0))
        (set! (az/index (az/field result inverse-bases) index) (inverse edges))
        (dotimes [local 4]
          (let [node (az/index cell local)
                offset (centroid-offset source cell local)
                direction (p/scale offset (/ 1.0 (p/length offset)))]
            (az/set-many!
              (az/index systems node) (matrix/add (az/index systems node) (matrix/outer direction direction))
              (az/index rhs node) (p/add (az/index rhs node) (p/scale direction -1.0)))))))
    (dotimes [node nodes]
      (set! (az/index rhs node) (matrix/apply-vector (inverse (az/index systems node)) (az/index rhs node))))
    (dotimes [index cells]
      (let [cell (az/index (az/field source cells) index)]
        (dotimes [local 4]
          (let [node (az/index cell local)
                offset (centroid-offset source cell local)
                distance (p/length offset)
                weight (/ (+ 1.0 (p/dot (az/index rhs node) (p/scale offset (/ 1.0 distance)))) distance)]
            (az/set-many!
              (az/index (az/index (az/field result cell-weights) index) local) weight
              (az/index sums node) (+ (az/index sums node) weight))))))
    (dotimes [index cells]
      (let [cell (az/index (az/field source cells) index)]
        (dotimes [local 4]
          (let [sum (az/index sums (az/index cell local))]
            (debug/assert (and (math/isFinite sum) (> sum 0.0)))
            (set! (az/index (az/index (az/field result cell-weights) index) local)
                  (/ (az/index (az/index (az/field result cell-weights) index) local) sum))))))
    result))

(az/defn destroy! :void
  [[render [:* Render]]]
  ((az/field heap/page_allocator free) (az/field render reference))
  ((az/field heap/page_allocator free) (az/field render bindings))
  ((az/field heap/page_allocator free) (az/field render faces))
  ((az/field heap/page_allocator free) (az/field render positions))
  ((az/field heap/page_allocator free) (az/field render normals))
  ((az/field heap/page_allocator free) (az/field render inverse-bases))
  ((az/field heap/page_allocator free) (az/field render cell-weights))
  ((az/field heap/page_allocator free) (az/field render gradients))
  ((az/field heap/page_allocator destroy) render))

(az/defn bind! :bool
  "Reject points outside the reference volume; never clamp or extrapolate weights.
  Point containment alone does not certify a triangle across a nonconvex cavity."
  [[render [:* Render]] [source [:* cache/Cache]] [index :usize] [point p/Vec3]]
  (debug/assert (< index (az/field (az/field render bindings) len)))
  (az/set-many!
    (az/field render ready) false
    (az/field (az/index (az/field render bindings) index) valid) false
    (az/index (az/field render reference) index) point)
  (when (ak/! (and (math/isFinite (az/field point x)) (math/isFinite (az/field point y))
                 (math/isFinite (az/field point z))))
    (ak/return false))
  (dotimes [cell-index (az/field (az/field source cells) len)]
    (let [cell (az/index (az/field source cells) cell-index)
          origin (az/index (az/field source reference) (az/index cell 0))
          coordinates (matrix/apply-vector (az/index (az/field render inverse-bases) cell-index)
                                            (p/add point (p/scale origin -1.0)))
          b (az/field coordinates x)
          c (az/field coordinates y)
          d (az/field coordinates z)
          a (- 1.0 b c d)]
      (when (and (>= a -1.0e-12) (>= b -1.0e-12) (>= c -1.0e-12) (>= d -1.0e-12))
        (set! (az/index (az/field render bindings) index)
              (Binding {:cell (ak/intCast cell-index)
                        :weights (az/init [a b c d] [:array 4 :f64]) :valid true}))
        (ak/return true))))
  false)

(az/defn set-face! :void
  [[render [:* Render]] [index :usize] [a :u32] [b :u32] [c :u32]]
  (let [points (az/field (az/field render reference) len)]
    (debug/assert (and (< index (az/field (az/field render faces) len))
                       (< a points) (< b points) (< c points))))
  (set! (az/index (az/field render faces) index) (az/init [a b c] [:array 3 :u32])))

(az/defn update! :bool
  "Transfer positions and recompute normals from the detailed deformed triangles.
  This is rendering interpolation, not a higher-order FEM or contact solve."
  [[render [:* Render]] [source [:* cache/Cache]] [tick :u32] [phong :bool]]
  (set! (az/field render ready) false)
  (when (or (ak/!= source (az/field render source))
            (>= tick (az/field source count))
            (ak/!= (az/field render source-nodes) (az/field (az/field source reference) len))
            (ak/!= (az/field render source-cells) (az/field (az/field source cells) len)))
    (ak/return false))
  (dotimes [point (az/field (az/field render bindings) len)]
    (when (ak/! (az/field (az/index (az/field render bindings) point) valid)) (ak/return false)))
  (dotimes [index (az/field (az/field render faces) len)]
    (let [face (az/index (az/field render faces) index)]
      (when (or (ak/== (az/index face 0) (az/index face 1))
                (ak/== (az/index face 1) (az/index face 2))
                (ak/== (az/index face 2) (az/index face 0)))
        (ak/return false))))
  (dotimes [node (az/field render source-nodes)]
    (set! (az/index (az/field render gradients) node) (matrix/zero)))
  (dotimes [index (az/field render source-cells)]
    (let [cell (az/index (az/field source cells) index)
          edges (basis (cache/position source tick (az/index cell 0))
                       (cache/position source tick (az/index cell 1))
                       (cache/position source tick (az/index cell 2))
                       (cache/position source tick (az/index cell 3)))
          gradient (multiply edges (az/index (az/field render inverse-bases) index))]
      (dotimes [local 4]
        (let [node (az/index cell local)
              weight (az/index (az/index (az/field render cell-weights) index) local)]
          (set! (az/index (az/field render gradients) node)
                (matrix/add (az/index (az/field render gradients) node) (matrix/scale gradient weight)))))))
  (az/set-many! (az/field render minimum-height) 1.0e30 (az/field render maximum-correction) 0.0)
  (dotimes [index (az/field (az/field render reference) len)]
    (let [binding (az/index (az/field render bindings) index)
          cell (az/index (az/field source cells) (az/field binding cell))
          point (az/index (az/field render reference) index)
          anchor (cache/position source tick (az/index cell 0))
          ^:var linear anchor
          ^:var correction (p/v 0.0 0.0 0.0)]
      (dotimes [local 4]
        (let [node (az/index cell local)
              weight (az/index (az/field binding weights) local)
              offset (p/add point (p/scale (az/index (az/field source reference) node) -1.0))]
          (az/set-many!
            linear (p/add linear (p/scale (p/add (cache/position source tick node) (p/scale anchor -1.0)) weight))
            correction (p/add correction (p/scale (matrix/apply-vector (az/index (az/field render gradients) node) offset)
                                                  (* 0.5 weight))))))
      (let [position (if phong (p/add linear correction) linear)]
        (when (ak/! (and (math/isFinite (az/field position x)) (math/isFinite (az/field position y))
                       (math/isFinite (az/field position z))))
          (ak/return false))
        (az/set-many!
          (az/index (az/field render positions) index) position
          (az/index (az/field render normals) index) (p/v 0.0 0.0 0.0)
          (az/field render minimum-height) (ak/min (az/field render minimum-height) (az/field position y))
          (az/field render maximum-correction) (ak/max (az/field render maximum-correction) (p/length correction))))))
  (dotimes [index (az/field (az/field render faces) len)]
    (let [face (az/index (az/field render faces) index)
          a (az/index (az/field render positions) (az/index face 0))
          b (az/index (az/field render positions) (az/index face 1))
          c (az/index (az/field render positions) (az/index face 2))
          normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))]
      (dotimes [local 3]
        (let [node (az/index face local)]
          (set! (az/index (az/field render normals) node) (p/add (az/index (az/field render normals) node) normal))))))
  (set! (az/field render ready) true)
  true)

(az/defn update-for-floor! :u32
  "0 rejects the frame, 1 uses Phong, 2 uses the contained linear interpolant.
  No point is clamped. A convex ground half-space contains every triangle when
  all its vertices satisfy the plane; this does not certify inter-body contact."
  [[render [:* Render]] [source [:* cache/Cache]] [tick :u32] [floor :bool]]
  (when (ak/! (update! render source tick true)) (ak/return 0))
  (when (or (ak/! floor) (>= (az/field render minimum-height) -1.0e-9)) (ak/return 1))
  (when (and (update! render source tick false) (>= (az/field render minimum-height) -1.0e-9))
    (ak/return 2))
  (set! (az/field render ready) false)
  0)

(az/defn inscribed-sphere! [:optional [:* Render]]
  "Detailed preview for a validated convex spherical cage. The smaller rest
  radius is explicit: no negative embedding weights or changes to FEM nodes.
  Non-spherical/cavity boundaries are rejected; general assets use bind!."
  [[source [:* cache/Cache]]]
  (let [rest (az/field source reference)
        faces (az/field source faces)
        ^:var lower (p/v 1.0e30 1.0e30 1.0e30)
        ^:var upper (p/v -1.0e30 -1.0e30 -1.0e30)]
    (dotimes [index (az/field rest len)]
      (let [point (az/index rest index)]
        (az/set-many!
          lower (p/v (ak/min (az/field lower x) (az/field point x))
                     (ak/min (az/field lower y) (az/field point y))
                     (ak/min (az/field lower z) (az/field point z)))
          upper (p/v (ak/max (az/field upper x) (az/field point x))
                     (ak/max (az/field upper y) (az/field point y))
                     (ak/max (az/field upper z) (az/field point z))))))
    (when (ak/== (az/field faces len) 0) (ak/return null))
    (let [center (p/scale (p/add lower upper) 0.5)
          first-point (az/index rest (az/index (az/index faces 0) 0))
          radius (p/length (p/add first-point (p/scale center -1.0)))
          ^:var inner-radius (ak/f64 radius)]
      (when (<= radius 1.0e-9) (ak/return null))
      (dotimes [index (az/field faces len)]
        (let [face (az/index faces index)
              a (az/index rest (az/index face 0))
              b (az/index rest (az/index face 1))
              c (az/index rest (az/index face 2))
              normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))
              length (p/length normal)]
          (when (<= length (* radius radius 1.0e-14)) (ak/return null))
          (let [unit (p/scale normal (/ 1.0 length))
                distance (p/dot unit (p/add a (p/scale center -1.0)))]
            (when (<= distance 0.0) (ak/return null))
            (set! inner-radius (ak/min inner-radius distance))
            (dotimes [local 3]
              (let [distance-to-center (p/length (p/add (az/index rest (az/index face local))
                                                        (p/scale center -1.0)))]
                (when (> (ak/abs (- distance-to-center radius)) (* radius 1.0e-7))
                  (ak/return null))))
            (dotimes [node (az/field rest len)]
              (when (> (p/dot unit (p/add (az/index rest node) (p/scale a -1.0))) (* radius 1.0e-10))
                (ak/return null))))))
      (let [longitude (ak/usize 48)
            latitude (ak/usize 24)
            count (+ 2 (* longitude (- latitude 1)))
            face-count (* 2 longitude (- latitude 1))
            render (create! source count face-count)
            display-radius (* inner-radius 0.999)
            ^:var written (ak/usize 0)]
        (when (ak/! (bind! render source 0 (p/add center (p/v 0.0 display-radius 0.0))))
          (destroy! render)
          (ak/return null))
        (dotimes [ring (- latitude 1)]
          (let [theta (/ (* math/pi (ak/as (ak/floatFromInt (+ ring 1)) :f64))
                         (ak/as (ak/floatFromInt latitude) :f64))]
            (dotimes [column longitude]
              (let [phi (/ (* 2.0 math/pi (ak/as (ak/floatFromInt column) :f64))
                           (ak/as (ak/floatFromInt longitude) :f64))
                    point (p/add center (p/scale (p/v (* (ak/sin theta) (ak/cos phi))
                                                     (ak/cos theta)
                                                     (* (ak/sin theta) (ak/sin phi))) display-radius))]
                (when (ak/! (bind! render source (+ 1 (* ring longitude) column) point))
                  (destroy! render)
                  (ak/return null))))))
        (when (ak/! (bind! render source (- count 1) (p/add center (p/v 0.0 (- display-radius) 0.0))))
          (destroy! render)
          (ak/return null))
        (dotimes [column longitude]
          (let [next (ak/mod (+ column 1) longitude)]
            (set-face! render written 0 (ak/intCast (+ 1 next)) (ak/intCast (+ 1 column)))
            (ak/+= written 1)
            (dotimes [ring (- latitude 2)]
              (let [a (+ 1 (* ring longitude) column)
                    b (+ 1 (* ring longitude) next)
                    c (+ a longitude)
                    d (+ b longitude)]
                (set-face! render written (ak/intCast a) (ak/intCast b) (ak/intCast c))
                (ak/+= written 1)
                (set-face! render written (ak/intCast b) (ak/intCast d) (ak/intCast c))
                (ak/+= written 1)))
            (set-face! render written (ak/intCast (+ 1 (* (- latitude 2) longitude) column))
                       (ak/intCast (+ 1 (* (- latitude 2) longitude) next)) (ak/intCast (- count 1)))
            (ak/+= written 1)))
        (set! (az/field render reference-inset) (- radius display-radius))
        (debug/assert (ak/== written face-count))
        render))))
