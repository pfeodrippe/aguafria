(ns field-lab.contact-mesh
  "Refittable triangle hierarchies, signed closest features and unsigned sweeps.
  Sign requires a closed, consistently oriented, non-self-intersecting surface."
  (:require [aguafria.std]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [field-lab.fem :as fem]
            [field-lab.build :as build]
            [field-lab.physics :as p]
            [field-lab.fem-job :as linear]
            [field-lab.nonlinear-job :as job]))

(defonce ccd-library (delay (build/configure-ccd!)))

(force ccd-library)

(az/defstruct CCDResult {:layout :extern}
  [[:status :u32] [:reserved :u32] [:time :f64] [:achieved-tolerance :f64]])

(az/defextern pitoco_aguafria_ccd_query :void
  {:zig/prefix "pub extern" :attrs #{:public}}
  [[kind :u32] [start [:pointer {:size :c :const? true} p/Vec3]]
   [end [:pointer {:size :c :const? true} p/Vec3]]
   [separation :f64] [tolerance :f64] [maximum-time :f64]
   [maximum-iterations :u32] [result [:c-pointer CCDResult]]])

(az/defn feature-sweep CCDResult
  "Conservative linear-trajectory CCD. Kind 0 vertex/face; kind 1 edge/edge.
  A reported time is a conservative bound; a possible hit may be a false positive."
  [[kind :u32] [a0 p/Vec3] [b0 p/Vec3] [c0 p/Vec3] [d0 p/Vec3]
   [a1 p/Vec3] [b1 p/Vec3] [c1 p/Vec3] [d1 p/Vec3]
   [separation :f64] [tolerance :f64] [maximum-time :f64] [maximum-iterations :u32]]
  (let [before (az/array-init [:array 4 p/Vec3] [a0 b0 c0 d0])
        after (az/array-init [:array 4 p/Vec3] [a1 b1 c1 d1])
        ^:var result (CCDResult {:status 2 :reserved 0 :time 0.0 :achieved-tolerance 0.0})]
    (pitoco_aguafria_ccd_query kind (ak/& (az/index before 0)) (ak/& (az/index after 0))
                     separation tolerance maximum-time maximum-iterations (ak/& result))
    result))

(defn sweep!
  "Query four vertices at start/end of a linear trajectory on normalized time [0,1].
  Vertex/face order: [vertex face-a face-b face-c]; edge/edge: [a0 a1 b0 b1].
  This reports possible contact, not a response impulse or an exact impact time."
  ([kind start end] (sweep! kind start end {}))
  ([kind start end {:keys [separation tolerance maximum-time maximum-iterations]
                    :or {separation 0.0 tolerance 1.0e-8 maximum-time 1.0 maximum-iterations 1000000}
                    :as options}]
   (let [code ({:vertex-face 0 :edge-edge 1} kind)
         points? #(and (vector? %) (= 4 (count %))
                       (every? (fn [v] (and (vector? v) (= 3 (count v))
                                            (every? (fn [x] (and (number? x) (Double/isFinite (double x)))) v))) %))
         finite? #(and (number? %) (Double/isFinite (double %)))]
     (when-not (and (map? options)
                    (every? #{:separation :tolerance :maximum-time :maximum-iterations} (keys options))
                    code (points? start) (points? end)
                    (every? finite? [separation tolerance maximum-time])
                    (<= 0.0 separation) (pos? tolerance) (< 0.0 maximum-time) (<= maximum-time 1.0)
                    (integer? maximum-iterations) (<= 1 maximum-iterations 1000000))
       (throw (ex-info "Invalid continuous collision query" {:kind kind})))
     (let [result (az/value (apply feature-sweep
                                  (concat [code] (map job/vector-map start) (map job/vector-map end)
                                          [(double separation) (double tolerance) (double maximum-time)
                                           maximum-iterations])))]
       (when (> (:status result) 1)
         (throw (ex-info "Native continuous collision query failed or exceeded its input domain" result)))
       (assoc (dissoc result :reserved :status)
              :possible-contact? (= 1 (:status result))
              :separation-certified? (pos? (:reserved result))
              :certificate ({1 :separating-axis 2 :non-coplanarity} (:reserved result))
              :precision-limited? (> (:achieved-tolerance result) tolerance))))))

(defn ccd-version
  "Record the actual linked native archive as well as pinned dependency sources."
  []
  (let [prefix (str (clojure.java.io/file (build/root) "build/ccd") "/")
        archive (last (filter #(and (string? %) (.startsWith ^String % prefix)
                                    (.endsWith ^String % "/libpitoco_ccd.a"))
                              (:zig-args (az/configuration))))]
    (when-not archive
      (throw (ex-info "No CCD archive configured" {})))
    (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                          (java.nio.file.Files/readAllBytes (.toPath (clojure.java.io/file archive))))]
      {:dependencies build/ccd-dependencies
       :archive-sha256 (apply str (map #(format "%02x" (bit-and % 255)) digest))})))

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

(az/defn create! [:* Surface]
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

(az/defn destroy! :void
  [[surface [:* Surface]]]
  ((az/field heap/page_allocator free) (az/field surface points))
  ((az/field heap/page_allocator free) (az/field surface faces))
  ((az/field heap/page_allocator free) (az/field surface tree))
  ((az/field heap/page_allocator free) (az/field surface leaves))
  ((az/field heap/page_allocator free) (az/field surface offsets))
  ((az/field heap/page_allocator free) (az/field surface incidents))
  ((az/field heap/page_allocator destroy) surface))

(az/defn set-point! :void
  "Initialization/batched refit setter; call refit! before queries."
  [[surface [:* Surface]] [node :usize] [point p/Vec3]]
  (set! (az/index (az/field surface points) node) point))

(az/defn set-face! :void
  [[surface [:* Surface]] [index :usize] [a :u32] [b :u32] [c :u32]]
  (set! (az/index (az/field surface faces) index) (az/array-init [:array 3 :u32] [a b c])))

(az/defn set-tree! :void
  [[surface [:* Surface]] [index :usize] [parent :usize]
   [left :usize] [right :usize] [face :usize] [leaf :bool]]
  (set! (az/index (az/field surface tree) index)
        (TreeNode {:parent parent :left left :right right :face face :leaf leaf
                    :bounds (Box {:lower (p/v 0.0 0.0 0.0) :upper (p/v 0.0 0.0 0.0)})}))
  (when leaf (set! (az/index (az/field surface leaves) face) index)))

(az/defn set-offset! :void
  [[surface [:* Surface]] [index :usize] [value :usize]]
  (set! (az/index (az/field surface offsets) index) value))

(az/defn set-incident! :void
  [[surface [:* Surface]] [index :usize] [face :u32]]
  (set! (az/index (az/field surface incidents) index) face))

(az/defn min-vector p/Vec3
  [[a p/Vec3] [b p/Vec3]]
  (p/v (ak/min (az/field a x) (az/field b x))
       (ak/min (az/field a y) (az/field b y))
       (ak/min (az/field a z) (az/field b z))))

(az/defn max-vector p/Vec3
  [[a p/Vec3] [b p/Vec3]]
  (p/v (ak/max (az/field a x) (az/field b x))
       (ak/max (az/field a y) (az/field b y))
       (ak/max (az/field a z) (az/field b z))))

(az/defn refit-node! :void
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

(az/defn refit! :void
  [[surface [:* Surface]]]
  (let [size (az/field (az/field surface tree) len)]
    (dotimes [index size]
      (refit-node! surface (- (- size 1) index)))))

(az/defstruct SplitBucket
  [[:bounds Box] [:count :usize]])

(az/defstruct BuildRange
  [[:node :usize] [:parent :usize] [:start :usize] [:count :usize] [:depth :usize]])

(az/defn vector-coordinate :f64
  [[value p/Vec3] [axis :usize]]
  (if (ak/== axis 0) (az/field value x)
      (if (ak/== axis 1) (az/field value y) (az/field value z))))

(az/defn face-bounds Box
  [[surface [:* Surface]] [index :usize]]
  (let [face (az/index (az/field surface faces) index)
        a (az/index (az/field surface points) (az/index face 0))
        b (az/index (az/field surface points) (az/index face 1))
        c (az/index (az/field surface points) (az/index face 2))]
    (Box {:lower (min-vector a (min-vector b c))
          :upper (max-vector a (max-vector b c))})))

(az/defn box-center p/Vec3
  [[bounds Box]]
  (p/add (p/scale (az/field bounds lower) 0.5) (p/scale (az/field bounds upper) 0.5)))

(az/defn union-box Box
  [[a Box] [b Box]]
  (Box {:lower (min-vector (az/field a lower) (az/field b lower))
        :upper (max-vector (az/field a upper) (az/field b upper))}))

(az/defn box-area :f64
  [[bounds Box]]
  (let [extent (p/add (az/field bounds upper) (p/scale (az/field bounds lower) -1.0))
        x (az/field extent x)
        y (az/field extent y)
        z (az/field extent z)]
    (* 2.0 (+ (* x y) (* y z) (* z x)))))

(az/defn split-bucket :usize
  [[bounds Box] [axis :usize] [lower :f64] [span :f64]]
  (let [offset (/ (- (vector-coordinate (box-center bounds) axis) lower) span)]
    (ak/intFromFloat (ak/min 11.0 (ak/max 0.0 (* 12.0 offset))))))

(az/defn partition-faces! :usize
  "Twelve-bucket SAH on the longest centroid axis. Single-face leaves retain
  the existing contact hierarchy ABI; coincident centers split by index."
  [[surface [:* Surface]] [order [:slice :usize]] [start :usize] [count :usize]]
  (let [initial (box-center (face-bounds surface (az/index order start)))
        ^:var centers (Box {:lower initial :upper initial})
        ^:var buckets (mem/zeroes (az/type [:array 12 SplitBucket]))
        ^:var prefix (mem/zeroes (az/type [:array 11 SplitBucket]))
        ^:var axis (ak/usize 0)]
    (dotimes [offset count]
      (let [center (box-center (face-bounds surface (az/index order (+ start offset))))]
        (set! centers (union-box centers (Box {:lower center :upper center})))))
    (let [extent (p/add (az/field centers upper) (p/scale (az/field centers lower) -1.0))]
      (dotimes [candidate 3]
        (when (> (vector-coordinate extent candidate) (vector-coordinate extent axis))
          (set! axis candidate)))
      (let [span (vector-coordinate extent axis)
            lower (vector-coordinate (az/field centers lower) axis)]
        (when (<= span 0.0) (ak/return (ak/divTrunc count 2)))
        (dotimes [offset count]
          (let [bounds (face-bounds surface (az/index order (+ start offset)))
                bucket (ak/& (az/index buckets (split-bucket bounds axis lower span)))]
            (set! (az/field bucket bounds)
                  (if (ak/== (az/field bucket count) 0) bounds
                      (union-box (az/field bucket bounds) bounds)))
            (ak/+= (az/field bucket count) 1)))
        (let [^:var accumulated (mem/zeroes (az/type SplitBucket))]
          (dotimes [i 11]
            (let [bucket (az/index buckets i)]
              (when (> (az/field bucket count) 0)
                (set! (az/field accumulated bounds)
                      (if (ak/== (az/field accumulated count) 0) (az/field bucket bounds)
                          (union-box (az/field accumulated bounds) (az/field bucket bounds))))
                (ak/+= (az/field accumulated count) (az/field bucket count)))
              (set! (az/index prefix i) accumulated))))
        (let [^:var accumulated (mem/zeroes (az/type SplitBucket))
              ^:var minimum (ak/f64 1.0e300)
              ^:var split (ak/usize 0)]
          (dotimes [offset 11]
            (let [i (- 11 offset)
                  bucket (az/index buckets i)
                  left (az/index prefix (- i 1))]
              (when (> (az/field bucket count) 0)
                (set! (az/field accumulated bounds)
                      (if (ak/== (az/field accumulated count) 0) (az/field bucket bounds)
                          (union-box (az/field accumulated bounds) (az/field bucket bounds))))
                (ak/+= (az/field accumulated count) (az/field bucket count)))
              (when (and (> (az/field left count) 0) (> (az/field accumulated count) 0))
                (let [cost (+ (* (ak/as (ak/floatFromInt (az/field left count)) :f64)
                                 (box-area (az/field left bounds)))
                              (* (ak/as (ak/floatFromInt (az/field accumulated count)) :f64)
                                 (box-area (az/field accumulated bounds))))]
                  (when (< cost minimum)
                    (az/set-many! minimum cost split (- i 1)))))))
          (let [^:var left-count (ak/usize 0)]
            (dotimes [offset count]
              (let [index (+ start offset)
                    face (az/index order index)]
                (when (<= (split-bucket (face-bounds surface face) axis lower span) split)
                  (az/set-many!
                    (az/index order index) (az/index order (+ start left-count))
                    (az/index order (+ start left-count)) face
                    left-count (+ left-count 1)))))
            (if (and (> left-count 0) (< left-count count)) left-count
                (ak/divTrunc count 2))))))))

(az/defn build-hierarchy! :bool
  "Build/refit an owned Surface entirely in native code. Validates finite bounded
  points and triangle indices before writing topology; accepts open/degenerate
  triangles for unsigned queries. Does not certify an oriented solid boundary."
  [[surface [:* Surface]]]
  (let [points (az/field surface points)
        faces (az/field surface faces)
        count (az/field faces len)]
    (when (or (ak/== count 0) (> count 80000) (ak/== (az/field points len) 0)
              (> (az/field points len) 20000)
              (ak/!= (az/field (az/field surface tree) len) (- (* 2 count) 1))
              (ak/!= (az/field (az/field surface leaves) len) count)
              (ak/!= (az/field (az/field surface offsets) len) (+ (az/field points len) 1))
              (ak/!= (az/field (az/field surface incidents) len) (* count 3)))
      (ak/return false))
    (dotimes [i (az/field points len)]
      (dotimes [axis 3]
        (let [value (vector-coordinate (az/index points i) axis)]
          (when (or (ak/! (math/isFinite value)) (> (ak/abs value) 1.0e50))
            (ak/return false)))))
    (dotimes [i count]
      (dotimes [corner 3]
        (when (>= (az/index (az/index faces i) corner) (az/field points len))
          (ak/return false))))
    ;; Build adjacency too: move-point! must work on a natively initialized surface.
    (dotimes [i (+ (az/field points len) 1)]
      (set! (az/index (az/field surface offsets) i) 0))
    (dotimes [i count]
      (dotimes [corner 3]
        (ak/+= (az/index (az/field surface offsets) (+ 1 (az/index (az/index faces i) corner))) 1)))
    (dotimes [i (az/field points len)]
      (ak/+= (az/index (az/field surface offsets) (+ i 1))
             (az/index (az/field surface offsets) i)))
    (let [cursor (fem/allocate :usize (az/field points len))]
      (defer ((az/field heap/page_allocator free) cursor))
      (dotimes [i (az/field points len)]
        (set! (az/index cursor i) (az/index (az/field surface offsets) i)))
      (dotimes [i count]
        (dotimes [corner 3]
          (let [vertex (az/index (az/index faces i) corner)]
            (az/set-many!
              (az/index (az/field surface incidents) (az/index cursor vertex)) (ak/intCast i)
              (az/index cursor vertex) (+ 1 (az/index cursor vertex)))))))
    (let [order (fem/allocate :usize count)
          ^:var pending (mem/zeroes (az/type [:array 128 BuildRange]))
          ^:var size (ak/usize 1)]
      (defer ((az/field heap/page_allocator free) order))
      (dotimes [i count] (set! (az/index order i) i))
      (set! (az/index pending 0) (BuildRange {:node 0 :parent 0 :start 0 :count count :depth 0}))
      (while (> size 0)
        (ak/-= size 1)
        (let [range (az/index pending size)
              node (az/field range node)
              start (az/field range start)
              range-count (az/field range count)
              depth (az/field range depth)]
          (if (ak/== range-count 1)
            (set-tree! surface node (az/field range parent) 0 0 (az/index order start) true)
            (let [;; Keep total depth at most 49 for the existing 64-entry query stack.
                  left-count (if (>= depth 32) (ak/divTrunc range-count 2)
                                 (partition-faces! surface order start range-count))
                  left (+ node 1)
                  right (+ node (* 2 left-count))]
              (debug/assert (< (+ size 1) 128))
              (set-tree! surface node (az/field range parent) left right 0 false)
              (az/set-many!
                (az/index pending size)
                (BuildRange {:node right :parent node :start (+ start left-count)
                             :count (- range-count left-count) :depth (+ depth 1)})
                (az/index pending (+ size 1))
                (BuildRange {:node left :parent node :start start
                             :count left-count :depth (+ depth 1)})
                size (+ size 2))))))
      (refit! surface)
      true)))

(az/defn packed-hierarchy-words :usize
  [[surface [:* Surface]]]
  (+ 4 (* 8 (az/field (az/field surface tree) len))
     (* 4 (az/field (az/field surface points) len))
     (* 4 (az/field (az/field surface faces) len))))

(az/defn float-word :u32
  [[value :f32]]
  (ak/bitCast value))

(az/defn pack-hierarchy! :usize
  "Bounded std430 word packet: header, 32-byte nodes, vec4 points, uvec4 faces.
  Bounds round outwards around the same float32 positions used by the viewport.
  Returns zero without writes on invalid input or insufficient caller capacity."
  [[surface [:* Surface]] [output [:c-pointer :u32]] [capacity :usize]]
  (let [points (az/field surface points)
        faces (az/field surface faces)
        tree (az/field surface tree)
        words (packed-hierarchy-words surface)]
    (when (or (ak/== output null) (< capacity words)
              (ak/== (az/field faces len) 0) (> (az/field faces len) 80000)
              (ak/== (az/field points len) 0) (> (az/field points len) 20000)
              (ak/!= (az/field tree len) (- (* 2 (az/field faces len)) 1)))
      (ak/return 0))
    (dotimes [i (az/field points len)]
      (dotimes [axis 3]
        (let [value (vector-coordinate (az/index points i) axis)]
          (when (or (ak/! (math/isFinite value)) (> (ak/abs value) 1.0e12))
            (ak/return 0)))))
    (dotimes [i (az/field faces len)]
      (dotimes [corner 3]
        (when (>= (az/index (az/index faces i) corner) (az/field points len))
          (ak/return 0))))
    (dotimes [i (az/field tree len)]
      (let [node (az/index tree i)
            bounds (az/field node bounds)]
        (dotimes [axis 3]
          (let [lower (vector-coordinate (az/field bounds lower) axis)
                upper (vector-coordinate (az/field bounds upper) axis)]
            (when (or (ak/! (math/isFinite lower)) (ak/! (math/isFinite upper))
                      (> lower upper) (> (ak/abs lower) 1.0e12) (> (ak/abs upper) 1.0e12))
              (ak/return 0))))
        (if (az/field node leaf)
          (when (>= (az/field node face) (az/field faces len)) (ak/return 0))
          (when (or (<= (az/field node left) i) (<= (az/field node right) i)
                    (>= (az/field node left) (az/field tree len))
                    (>= (az/field node right) (az/field tree len)))
            (ak/return 0)))))
    (az/set-many!
      (az/index output 0) (ak/intCast (az/field tree len))
      (az/index output 1) (ak/intCast (az/field points len))
      (az/index output 2) (ak/intCast (az/field faces len))
      (az/index output 3) 0)
    (dotimes [i (az/field tree len)]
      (let [node (az/index tree i)
            bounds (az/field node bounds)
            base (+ 4 (* 8 i))]
        (dotimes [axis 3]
          (let [lower (ak/as (ak/floatCast (vector-coordinate (az/field bounds lower) axis)) :f32)
                upper (ak/as (ak/floatCast (vector-coordinate (az/field bounds upper) axis)) :f32)]
            (az/set-many!
              (az/index output (+ base axis))
              (float-word (math/nextAfter :f32 lower (- (math/inf :f32))))
              (az/index output (+ base 4 axis))
              (float-word (math/nextAfter :f32 upper (math/inf :f32))))))
        (az/set-many!
          (az/index output (+ base 3)) (if (az/field node leaf) 0xffffffff (ak/intCast (az/field node left)))
          (az/index output (+ base 7)) (ak/intCast (if (az/field node leaf) (az/field node face)
                                                    (az/field node right))))))
    (let [base (+ 4 (* 8 (az/field tree len)))]
      (dotimes [i (az/field points len)]
        (dotimes [axis 3]
          (set! (az/index output (+ base (* 4 i) axis))
                (float-word (ak/floatCast (vector-coordinate (az/index points i) axis)))))
        (set! (az/index output (+ base (* 4 i) 3)) 0)))
    (let [base (+ 4 (* 8 (az/field tree len)) (* 4 (az/field points len)))]
      (dotimes [i (az/field faces len)]
        (dotimes [corner 3]
          (set! (az/index output (+ base (* 4 i) corner)) (az/index (az/index faces i) corner)))
        (set! (az/index output (+ base (* 4 i) 3)) 0)))
    words))

(az/defn move-point! :void
  "Update incident leaves and ancestors after a contact correction."
  [[surface [:* Surface]] [vertex :usize] [point p/Vec3]]
  (set-point! surface vertex point)
  (let [start (az/index (az/field surface offsets) vertex)
        end (az/index (az/field surface offsets) (+ vertex 1))]
    (dotimes [offset (- end start)]
      (let [face (az/index (az/field surface incidents) (+ start offset))
            ^:var index (ak/usize (az/index (az/field surface leaves) face))]
        (while true
          (refit-node! surface index)
          (when (ak/== index 0) (ak/break))
          (set! index (az/field (az/index (az/field surface tree) index) parent)))))))

(az/defn box-distance :f64
  [[bounds Box] [point p/Vec3]]
  (let [sum (max-vector (p/v 0.0 0.0 0.0)
                        (max-vector (p/add (az/field bounds lower) (p/scale point -1.0))
                                    (p/add point (p/scale (az/field bounds upper) -1.0))))]
    (p/dot sum sum)))

(az/defn bounds-contain? :bool
  "Broad-phase test against the root box, not a solid-interior classification."
  [[surface [:* Surface]] [point p/Vec3]]
  (<= (box-distance (az/field (az/index (az/field surface tree) 0) bounds) point) 1.0e-24))

(az/defn unit-normal p/Vec3
  [[surface [:* Surface]] [index :usize]]
  (let [face (az/index (az/field surface faces) index)
        a (az/index (az/field surface points) (az/index face 0))
        b (az/index (az/field surface points) (az/index face 1))
        c (az/index (az/field surface points) (az/index face 2))
        normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))]
    (p/scale normal (/ 1.0 (ak/max 1.0e-30 (p/length normal))))))

(az/defn triangle-closest Closest
  "Interior projection plus all three closed segments covers every Voronoi region."
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

(az/defn pseudo-normal p/Vec3
  "Bærentzen–Aanæs: incident-angle weights at vertices; both normals at edges."
  [[surface [:* Surface]] [closest Closest]]
  (let [face (az/index (az/field surface faces) (az/field closest face))
        weights (az/field closest weights)
        ^:var positives (ak/usize 0)
        ^:var first (ak/usize 0)
        ^:var second (ak/usize 0)]
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

(az/defn closest-point Closest
  [[surface [:* Surface]] [point p/Vec3]]
  (let [^:var stack (ak/as ak/undefined [:array 64 :usize])
        ^:var size (ak/usize 1)
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
          weights (az/field result weights)
          face-interior (and (> (az/field weights x) 0.0)
                             (> (az/field weights y) 0.0)
                             (> (az/field weights z) 0.0))
          delta (p/add point (p/scale (az/field result point) -1.0))
          distance (ak/sqrt (az/field result squared-distance))
          inside (< (p/dot pseudo delta) 0.0)]
      (az/set-many!
        (az/field result signed-distance) (if inside (- distance) distance)
        ;; Within a face, the distance gradient is its geometric normal.
        ;; Normalizing a near-zero point difference amplifies tangential roundoff.
        ;; Edge/vertex Voronoi regions still require the signed radial direction.
        (az/field result normal) (if (and (ak/! face-interior) (> distance 1.0e-15))
                                  (p/scale delta (if inside (/ -1.0 distance) (/ 1.0 distance))) pseudo)))
    result))

(az/defstruct SegmentClosest {:layout :extern}
  [[:a p/Vec3] [:b p/Vec3] [:s :f64] [:t :f64] [:squared-distance :f64]])

(az/defn segment-pair SegmentClosest
  [[a p/Vec3] [u p/Vec3] [b p/Vec3] [v p/Vec3] [s :f64] [t :f64]]
  (let [left (p/add a (p/scale u s))
        right (p/add b (p/scale v t))
        delta (p/add left (p/scale right -1.0))]
    (SegmentClosest {:a left :b right :s s :t t :squared-distance (p/dot delta delta)})))

(az/defn segment-closest SegmentClosest
  "Minimum over four endpoint projections and the interior stationary pair.
  Cross-product determinant avoids subtracting nearly equal squared dot products.
  Parallel and zero-length segments are covered by the endpoint candidates."
  [[a p/Vec3] [a-end p/Vec3] [b p/Vec3] [b-end p/Vec3]]
  (let [u (p/add a-end (p/scale a -1.0))
        v (p/add b-end (p/scale b -1.0))
        r (p/add a (p/scale b -1.0))
        uu (p/dot u u)
        vv (p/dot v v)
        ^:var result (segment-pair a u b v 0.0 0.0)]
    (dotimes [index 4]
      (let [endpoint (ak/as (if (ak/== (mod index 2) 0) 0.0 1.0) :f64)
            on-a (< index 2)
            offset (if on-a (p/add r (p/scale u endpoint))
                                (p/add (p/scale r -1.0) (p/scale v endpoint)))
            length-squared (if on-a vv uu)
            parameter (if (> length-squared 0.0)
                        (ak/max 0.0 (ak/min 1.0 (/ (p/dot offset (if on-a v u)) length-squared))) 0.0)
            candidate (segment-pair a u b v (if on-a endpoint parameter) (if on-a parameter endpoint))]
        (when (< (az/field candidate squared-distance) (az/field result squared-distance))
          (set! result candidate))))
    (let [normal (p/cross u v)
          determinant (p/dot normal normal)]
      (when (> determinant 0.0)
        (let [s (/ (p/dot (p/cross v r) normal) determinant)
              t (/ (p/dot (p/cross u r) normal) determinant)]
          (when (and (>= s 0.0) (<= s 1.0) (>= t 0.0) (<= t 1.0))
            (let [candidate (segment-pair a u b v s t)]
              (when (< (az/field candidate squared-distance) (az/field result squared-distance))
                (set! result candidate)))))))
    result))

(az/defstruct MotionSurface
  "Borrows immutable source topology; owns endpoint positions and swept boxes."
  [[:source [:* Surface]] [:start [:slice p/Vec3]] [:end [:slice p/Vec3]]
   [:bounds [:slice Box]] [:ready :bool]])

(az/defstruct MeshSweepResult {:layout :extern}
  [[:status :u32] [:visits :u32] [:queries :u32] [:candidates :u32]
   [:face-a :u32] [:face-b :u32] [:feature :u32] [:reserved :u32]
   [:time :f64] [:achieved-tolerance :f64]])

(az/defn create-motion! [:* MotionSurface]
  [[source [:* Surface]]]
  (let [motion (catch ((az/field heap/page_allocator create) MotionSurface)
                 (debug/panic "Unable to allocate swept contact mesh" []))
        count (az/field (az/field source points) len)]
    (set! (az/deref motion)
          (MotionSurface {:source source :start (fem/allocate p/Vec3 count)
                          :end (fem/allocate p/Vec3 count)
                          :bounds (fem/allocate Box (az/field (az/field source tree) len))
                          :ready false}))
    (dotimes [i count]
      (az/set-many!
        (az/index (az/field motion start) i) (az/index (az/field source points) i)
        (az/index (az/field motion end) i) (az/index (az/field source points) i)))
    motion))

(az/defn destroy-motion! :void
  [[motion [:* MotionSurface]]]
  ((az/field heap/page_allocator free) (az/field motion start))
  ((az/field heap/page_allocator free) (az/field motion end))
  ((az/field heap/page_allocator free) (az/field motion bounds))
  ((az/field heap/page_allocator destroy) motion))

(az/defn set-motion-point! :void
  [[motion [:* MotionSurface]] [node :usize] [start p/Vec3] [end p/Vec3]]
  (az/set-many!
    (az/field motion ready) false
    (az/index (az/field motion start) node) start
    (az/index (az/field motion end) node) end))

(az/defn refit-motion! :bool
  "Endpoint extrema enclose every point of a linearly deforming triangle."
  [[motion [:* MotionSurface]]]
  (let [source (az/field motion source)
        count (az/field (az/field source tree) len)]
    (set! (az/field motion ready) false)
    (dotimes [i (az/field (az/field motion start) len)]
      (dotimes [axis 3]
        (let [a (fem/component (az/index (az/field motion start) i) axis)
              b (fem/component (az/index (az/field motion end) i) axis)]
          (when (or (ak/! (math/isFinite a)) (ak/! (math/isFinite b))
                    (> (ak/abs a) 1.0e50) (> (ak/abs b) 1.0e50))
            (ak/return false)))))
    (dotimes [offset count]
      (let [index (- count 1 offset)
            node (az/index (az/field source tree) index)]
        (if (az/field node leaf)
          (let [face (az/index (az/field source faces) (az/field node face))
                first (az/index (az/field motion start) (az/index face 0))
                ^:var lower first
                ^:var upper first]
            (dotimes [local 3]
              (let [a (az/index (az/field motion start) (az/index face local))
                    b (az/index (az/field motion end) (az/index face local))]
                (az/set-many!
                  lower (min-vector lower (min-vector a b))
                  upper (max-vector upper (max-vector a b)))))
            (set! (az/index (az/field motion bounds) index) (Box {:lower lower :upper upper})))
          (let [a (az/index (az/field motion bounds) (az/field node left))
                b (az/index (az/field motion bounds) (az/field node right))]
            (set! (az/index (az/field motion bounds) index)
                  (Box {:lower (min-vector (az/field a lower) (az/field b lower))
                        :upper (max-vector (az/field a upper) (az/field b upper))}))))))
    (set! (az/field motion ready) true)
    true))

(az/defn boxes-near? :bool
  "Outward-round the separation expansion so rounding cannot prune a true pair."
  [[a Box] [b Box] [separation :f64]]
  (dotimes [axis 3]
    (let [upper-a (math/nextAfter :f64 (+ (fem/component (az/field a upper) axis) separation) 1.0e300)
          upper-b (math/nextAfter :f64 (+ (fem/component (az/field b upper) axis) separation) 1.0e300)]
      (when (or (> (fem/component (az/field b lower) axis) upper-a)
                (> (fem/component (az/field a lower) axis) upper-b))
        (ak/return false))))
  true)

(az/defstruct FacePairsReport {:layout :extern}
  [[:status :u32] [:count :usize] [:visits :u32]])

(az/defn near-face-pairs! FacePairsReport
  "Collect static BVH leaf pairs within a coordinate-wise expanded box.
  Status 1 means output/work capacity; candidates still require distance tests."
  [[a [:* Surface]] [b [:* Surface]] [separation :f64] [pairs [:slice [:array 2 :u32]]]]
  (let [^:var report (FacePairsReport {:status 0 :count 0 :visits 0})
        ^:var stack (ak/as ak/undefined [:array 128 [:array 2 :usize]])
        ^:var size (ak/usize 1)]
    (set! (az/index stack 0) (az/array-init [:array 2 :usize] [0 0]))
    (while (> size 0)
      (when (>= (az/field report visits) 100000)
        (set! (az/field report status) 1)
        (ak/return report))
      (ak/-= size 1)
      (ak/+= (az/field report visits) 1)
      (let [pair (az/index stack size)
            ia (az/index pair 0)
            ib (az/index pair 1)
            left (az/index (az/field a tree) ia)
            right (az/index (az/field b tree) ib)]
        (when (boxes-near? (az/field left bounds) (az/field right bounds) separation)
          (if (and (az/field left leaf) (az/field right leaf))
            (do
              (when (ak/== (az/field report count) (az/field pairs len))
                (set! (az/field report status) 1)
                (ak/return report))
              (set! (az/index pairs (az/field report count))
                    (az/array-init [:array 2 :u32] [(ak/intCast (az/field left face)) (ak/intCast (az/field right face))]))
              (ak/+= (az/field report count) 1))
            (let [split-a (or (az/field right leaf) (ak/! (az/field left leaf)))]
              (when (> (+ size 2) 128)
                (set! (az/field report status) 1)
                (ak/return report))
              (az/set-many!
                (az/index stack size)
                (az/array-init [:array 2 :usize] [(if split-a (az/field left right) ia)
                                                 (if split-a ib (az/field right right))])
                (az/index stack (+ size 1))
                (az/array-init [:array 2 :usize] [(if split-a (az/field left left) ia)
                                                 (if split-a ib (az/field right left))])
                size (+ size 2)))))))
    report))

(az/defstruct SweepFeature
  [[:kind :u32] [:nodes [:array 4 :u32]] [:left [:array 4 :bool]]])

(az/defn triangle-feature SweepFeature
  [[a [:array 3 :u32]] [b [:array 3 :u32]] [index :usize]]
  (when (< index 3)
    (ak/return (SweepFeature {:kind 0 :nodes [(az/index a index) (az/index b 0) (az/index b 1) (az/index b 2)]
                              :left [true false false false]})))
  (when (< index 6)
    (ak/return (SweepFeature {:kind 0 :nodes [(az/index b (- index 3)) (az/index a 0) (az/index a 1) (az/index a 2)]
                              :left [false true true true]})))
  (let [edge-a (ak/divTrunc (- index 6) 3)
        edge-b (mod (- index 6) 3)]
    (SweepFeature {:kind 1 :nodes [(az/index a edge-a) (az/index a (mod (+ edge-a 1) 3))
                                  (az/index b edge-b) (az/index b (mod (+ edge-b 1) 3))]
                   :left [true true false false]})))

(az/defn query-triangle-pair! :void
  [[a [:* MotionSurface]] [b [:* MotionSurface]] [face-a :u32] [face-b :u32]
   [separation :f64] [tolerance :f64] [maximum-work :u32] [maximum-iterations :u32]
   [report [:* MeshSweepResult]]]
  (dotimes [index 15]
    (when (>= (+ (az/field report visits) (az/field report queries)) maximum-work)
      (az/set-many! (az/field report status) 4 (az/field report time) 0.0)
      (ak/return))
    (let [feature (triangle-feature (az/index (az/field (az/field a source) faces) face-a)
                                    (az/index (az/field (az/field b source) faces) face-b) index)
          ^:var start (ak/as ak/undefined [:array 4 p/Vec3])
          ^:var end (ak/as ak/undefined [:array 4 p/Vec3])]
      (dotimes [i 4]
        (let [motion (if (az/index (az/field feature left) i) a b)
              node (az/index (az/field feature nodes) i)]
          (az/set-many!
            (az/index start i) (az/index (az/field motion start) node)
            (az/index end i) (az/index (az/field motion end) node))))
      (let [result (feature-sweep (az/field feature kind)
                                  (az/index start 0) (az/index start 1) (az/index start 2) (az/index start 3)
                                  (az/index end 0) (az/index end 1) (az/index end 2) (az/index end 3)
                                  separation tolerance (ak/min 1.0 (az/field report time)) maximum-iterations)]
        (ak/+= (az/field report queries) 1)
        (when (> (az/field result status) 1)
          (az/set-many! (az/field report status) 3 (az/field report time) 0.0)
          (ak/return))
        (when (and (ak/== (az/field result status) 1) (< (az/field result time) (az/field report time)))
          (az/set-many!
            (az/field report status) 1
            (az/field report time) (az/field result time)
            (az/field report achieved-tolerance) (az/field result achieved-tolerance)
            (az/field report face-a) face-a
            (az/field report face-b) face-b
            (az/field report feature) (ak/intCast index)))
        (when (ak/== (az/field report time) 0.0) (ak/return))))))

(az/defn sweep-surfaces MeshSweepResult
  "Paired swept BVHs, six vertex/face and nine edge/edge queries per leaf pair.
  Status 0 clear, 1 possible contact, 2 invalid/unrefitted, 3 primitive failure,
  4 work/stack budget. A clear path assumes initially disjoint triangle surfaces;
  these feature predicates do not detect arbitrary pre-existing intersections."
  [[a [:* MotionSurface]] [b [:* MotionSurface]]
   [separation :f64] [tolerance :f64] [maximum-work :u32] [maximum-iterations :u32]]
  (let [^:var report (MeshSweepResult {:status 2 :visits 0 :queries 0 :candidates 0
                                      :face-a 0 :face-b 0 :feature 0 :reserved 0
                                      :time 0.0 :achieved-tolerance 0.0})
        ^:var stack (ak/as ak/undefined [:array 128 [:array 2 :usize]])
        ^:var size (ak/usize 1)]
    (when (or (ak/! (az/field a ready)) (ak/! (az/field b ready))
              (ak/! (math/isFinite separation)) (< separation 0.0) (> separation 1.0e50)
              (ak/! (math/isFinite tolerance)) (<= tolerance 0.0)
              (ak/== maximum-work 0) (> maximum-work 10000000)
              (ak/== maximum-iterations 0) (> maximum-iterations 1000000))
      (ak/return report))
    (az/set-many!
      (az/field report status) 0
      (az/field report time) (math/inf :f64)
      (az/index stack 0) (az/array-init [:array 2 :usize] [0 0]))
    (while (> size 0)
      (when (>= (+ (az/field report visits) (az/field report queries)) maximum-work)
        (az/set-many! (az/field report status) 4 (az/field report time) 0.0)
        (ak/return report))
      (ak/-= size 1)
      (ak/+= (az/field report visits) 1)
      (let [pair (az/index stack size)
            ia (az/index pair 0)
            ib (az/index pair 1)
            bounds-a (az/index (az/field a bounds) ia)
            bounds-b (az/index (az/field b bounds) ib)
            node-a (az/index (az/field (az/field a source) tree) ia)
            node-b (az/index (az/field (az/field b source) tree) ib)]
        (when (boxes-near? bounds-a bounds-b separation)
          (if (and (az/field node-a leaf) (az/field node-b leaf))
            (do
              (ak/+= (az/field report candidates) 1)
              (query-triangle-pair! a b (ak/intCast (az/field node-a face)) (ak/intCast (az/field node-b face))
                                    separation tolerance maximum-work maximum-iterations (ak/& report))
              (when (or (> (az/field report status) 1) (ak/== (az/field report time) 0.0))
                (ak/return report)))
            (let [span-a (p/add (az/field bounds-a upper) (p/scale (az/field bounds-a lower) -1.0))
                  span-b (p/add (az/field bounds-b upper) (p/scale (az/field bounds-b lower) -1.0))
                  split-a (or (az/field node-b leaf)
                              (and (ak/! (az/field node-a leaf))
                                   (>= (p/dot span-a span-a) (p/dot span-b span-b))))]
              (when (> (+ size 2) 128)
                (az/set-many! (az/field report status) 4 (az/field report time) 0.0)
                (ak/return report))
              (az/set-many!
                (az/index stack size)
                (az/array-init [:array 2 :usize] [(if split-a (az/field node-a right) ia)
                                                 (if split-a ib (az/field node-b right))])
                (az/index stack (+ size 1))
                (az/array-init [:array 2 :usize] [(if split-a (az/field node-a left) ia)
                                                 (if split-a ib (az/field node-b left))])
                size (+ size 2)))))))
    report))

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

(defn- populate-surface!
  [surface points faces]
  (let [incidents (reduce (fn [result [index face]]
                            (reduce #(update %1 %2 conj index) result face))
                          (vec (repeat (count points) [])) (map-indexed vector faces))]
    (doseq [[index point] (map-indexed vector points)]
      (set-point! surface index (job/vector-map point)))
    (doseq [[index [a b c]] (map-indexed vector faces)]
      (set-face! surface index a b c))
    (doseq [[index {:keys [parent left right face leaf]}] (map-indexed vector (hierarchy points faces))]
      (set-tree! surface index parent left right face leaf))
    (doseq [[index offset] (map-indexed vector (reductions + 0 (map count incidents)))]
      (set-offset! surface index offset))
    (doseq [[index face] (map-indexed vector (mapcat identity incidents))]
      (set-incident! surface index face))
    (refit! surface)))

(defn build!
  "Build a private closed contact surface; caller owns and must destroy the result."
  [points faces]
  (validate! points faces)
  (az/await! 'field-lab.contact-mesh)
  (let [surface (create! (count points) (count faces))]
    (try
      (populate-surface! surface points faces)
      surface
      (catch Throwable error
        (destroy! surface)
        (throw error)))))

(defn with-motion!
  "Call f with an owned, refitted linear motion; destroy it after f returns.
  Accept open triangles and geometric degeneracy for unsigned CCD. The borrowed
  topology is private; neither the motion nor its source may escape this scope."
  [{:keys [start end faces] :as mesh} f]
  (let [points? #(and (vector? %) (<= 1 (count %) 20000)
                       (every? (fn [point]
                                 (and (vector? point) (= 3 (count point))
                                      (every? (fn [x] (and (number? x) (Double/isFinite (double x))
                                                           (<= (abs (double x)) 1.0e50))) point))) %))]
    (when-not (and (map? mesh) (every? #{:start :end :faces} (keys mesh))
                   (points? start) (points? end) (= (count start) (count end))
                   (vector? faces) (<= 1 (count faces) 80000)
                   (every? (fn [face]
                             (and (vector? face) (= 3 (count face)) (= 3 (count (set face)))
                                  (every? #(and (integer? %) (<= 0 %) (< % (count start))) face))) faces))
      (throw (ex-info "Invalid swept triangle mesh" {}))))
  (az/await! 'field-lab.contact-mesh)
  (let [source (create! (count start) (count faces))]
    (try
      (populate-surface! source start faces)
      (let [motion (create-motion! source)]
        (try
          (doseq [[index point] (map-indexed vector start)]
            (set-motion-point! motion index (job/vector-map point) (job/vector-map (end index))))
          (when-not (refit-motion! motion)
            (throw (ex-info "Could not refit swept triangle mesh" {})))
          (f motion)
          (finally (destroy-motion! motion))))
      (finally (destroy! source)))))

(defn sweep-meshes!
  "Conservative boundary sweep of two triangle meshes over normalized time [0,1].
  Each mesh is {:start points :end points :faces triangles}. Trajectories must
  be linear and surfaces initially disjoint. Does not test solid containment or
  arbitrary pre-existing intersections (initial feature coincidences do report).
  Throws on invalid input or incomplete work; exhaustion must never mean clear."
  ([left right] (sweep-meshes! left right {}))
  ([left right {:keys [separation tolerance maximum-work maximum-iterations]
                :or {separation 0.0 tolerance 1.0e-8 maximum-work 1000000 maximum-iterations 1000000}
                :as options}]
   (when-not (and (map? options)
                  (every? #{:separation :tolerance :maximum-work :maximum-iterations} (keys options))
                  (number? separation) (Double/isFinite (double separation)) (<= 0.0 separation 1.0e50)
                  (number? tolerance) (Double/isFinite (double tolerance)) (pos? tolerance)
                  (integer? maximum-work) (<= 1 maximum-work 10000000)
                  (integer? maximum-iterations) (<= 1 maximum-iterations 1000000))
     (throw (ex-info "Invalid swept mesh options" {})))
   (with-motion! left
     (fn [a]
       (with-motion! right
         (fn [b]
           (let [result (az/value (sweep-surfaces a b (double separation) (double tolerance)
                                                 maximum-work maximum-iterations))
                 status (:status result)
                 hit? (= 1 status)]
             (when (> status 1)
               (throw (ex-info "Swept mesh query did not complete"
                               (assoc result :reason ({2 :invalid-or-unrefitted
                                                       3 :primitive-failure 4 :work-budget} status)))))
             (cond-> (assoc (dissoc result :status :reserved :face-a :face-b :feature)
                            :possible-contact? hit?
                            :precision-limited? (and hit? (> (:achieved-tolerance result) tolerance)))
               hit? (assoc :faces [(:face-a result) (:face-b result)]
                           :feature {:kind (if (< (:feature result) 6) :vertex-face :edge-edge)
                                     :index (:feature result)})))))))))
