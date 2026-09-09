(ns racing-game.track
  "Blender track sampling, paved cross-sections and continuous pit routes.
  Surface helpers use metres; legacy race poses use kilometres/lane units."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]))

(az/defconst tau :f32 6.2831855)

(az/defconst projection-samples :usize 192)

(az/defconst surface-segments :usize 2048)

(az/defconst surface-columns :usize 6)

(az/defn smootherstep :- :f32 [[value :f32]]
  (let [t (ak/max 0.0 (ak/min 1.0 value))]
    (* t t t (+ (* t (- (* t 6.0) 15.0)) 10.0))))

(az/defn pit-blend :- :f32 [[progress :f32]]
  (let [wrapped (- progress (std-math/floor progress))
        q (if (< wrapped 0.5) (+ wrapped 1.0) wrapped)]
    (* (smootherstep (/ (- q 0.94) 0.025))
       (smootherstep (/ (- 1.055 q) 0.030)))))

(az/defn pit-offset
  "Fast lane is 17m from the circuit; work bays are another 6m outward.
  Both taper onto the actual circuit, including its elevation, at each end."
  :- :f32 [[progress :f32] [work-offset :f32]]
  (* (+ 17.0 work-offset) (pit-blend progress)))

(az/defn surface-boundary
  "Sorted lateral metres: shoulder, main road, verge, pit apron, shoulder.
  Inactive verge/apron strips collapse and emit no degenerate triangles."
  :- :f32 [[progress :f32] [column :usize]]
  (let [blend (pit-blend progress)]
    (cond
      (ak/== column 0) -35.0
      (ak/== column 1) -6.5
      (ak/== column 2) 6.5
      ;; Preserve a 2.5m footprint margin through tapers, not only at full width.
      (ak/== column 3) (ak/max 6.5 (ak/min (* 13.0 blend) (- (* 17.0 blend) 2.5)))
      (ak/== column 4) (ak/max 6.5 (ak/max (* 27.0 blend) (+ (* 23.0 blend) 2.5)))
      :else 35.0)))

(az/defn surface-asphalt? :- :bool [[strip :usize]]
  (or (ak/== strip 1) (ak/== strip 3)))

(az/defn surface-point :- circuit/Sample [[progress :f32] [column :usize]]
  (circuit/at-distance (* progress circuit/length-metres) (surface-boundary progress column)))

(az/defn pit-point :- circuit/Sample [[progress :f32] [work-offset :f32]]
  (let [distance (* progress circuit/length-metres)
        ^:var p (circuit/at-distance distance (pit-offset progress work-offset))
        before (/ (- distance 1.0) circuit/length-metres)
        after (/ (+ distance 1.0) circuit/length-metres)
        a (circuit/at-distance (- distance 1.0) (pit-offset before work-offset))
        b (circuit/at-distance (+ distance 1.0) (pit-offset after work-offset))]
    (set! (az/field p heading) (std-math/atan2 (- (az/field b y) (az/field a y))
                                             (- (az/field b x) (az/field a x))))
    p))

(az/defstruct Pose
  "Centerline position, lane offset, and forward heading at normalized progress."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:heading :f32]])

(az/defstruct Projection
  "Nearest centerline progress and signed lane for an arbitrary world point."
  {:layout :extern}
  [[:progress :f32]
   [:lane :f32]
   [:distance_squared :f32]])

(az/defn wrap-progress
  "Wrap any signed progress onto the closed circuit's [0, 1) interval."
  :-
  :f32
  [[progress :f32]]
  (- progress (std-math/floor progress)))

(az/defn pose
  "Sample the 4309m Blender circuit. Output x/y are kilometres, lane units 50m."
  :- Pose [[progress :f32] [lane :f32]]
  (let [sample (circuit/at-distance (* (wrap-progress progress) 4309.0) (* lane 50.0))]
    (Pose {:x (* (az/field sample x) 0.001)
           :y (* (az/field sample y) 0.001)
           :heading (az/field sample heading)})))

(az/defn elevation
  "Blender road elevation in world kilometres."
  :- :f32 [[progress :f32]]
  (* 0.001 (az/field (circuit/at-distance (* (wrap-progress progress) 4309.0) 0.0) z)))

(az/defn progress-step
  "Speed is km/s internally: .083333 means 300 km/h, not laps per second."
  :- :f32 [[speed :f32] [seconds :f32]]
  (/ (* speed seconds) 4.309))

(az/defn corner-speed
  "Physical curvature limit in km/s, using a 3g lateral-acceleration envelope."
  :- :f32 [[progress :f32] [grip :f32]]
  (let [a (pose (- progress 0.001) 0.0) b (pose (+ progress 0.001) 0.0)
        delta (- (az/field b heading) (az/field a heading))
        angle (ak/abs (std-math/atan2 (std-math/sin delta) (std-math/cos delta)))
        curvature (/ angle 8.618)]
    (ak/min 0.095 (* 0.001 (std-math/sqrt (/ (* 30.0 grip) (ak/max curvature 0.00001)))))))

(az/defn speed-envelope
  "Look 150 metres ahead and brake before a bend instead of clipping speed in it."
  :- :f32 [[progress :f32] [grip :f32]]
  (let [^{:var :f32} limit (corner-speed progress grip)]
    (dotimes [i 6]
      (let [distance (* (ak/as :f32 (ak/floatFromInt (+ i 1))) 25.0)
            corner (* 1000.0 (corner-speed (+ progress (/ distance 4309.0)) grip))
            approaching (* 0.001 (std-math/sqrt (+ (* corner corner) (* 60.0 distance))))]
        (set! limit (ak/min limit approaching))))
    limit))

(az/defn pit-pose
  "Canonical pit route shared with Box3D's paved surface; legacy lane units 50m."
  :- Pose [[progress :f32] [lane :f32]]
  (let [p (pit-point progress (* (- lane 0.17) 133.33333))]
    (Pose {:x (* (az/field p x) 0.001) :y (* (az/field p y) 0.001)
           :heading (az/field p heading)})))

(az/defn center-distance-squared
  :-
  :f32
  [[x :f32]
   [y :f32]
   [progress :f32]]
  (let [center (pose progress 0.0)
        dx (- x (az/field center x))
        dy (- y (az/field center y))]
    (+ (* dx dx) (* dy dy))))

(az/defn projection-centers-type
  "Build the immutable coarse search table at compile time from the authored
  curve. Editing the curve regenerates this dependency; no runtime warm-up."
  :- [:array projection-samples [:array 2 :f32]] []
  (ak/setEvalBranchQuota 1000000)
  (let [^{:var [:array projection-samples [:array 2 :f32]]} points ak/undefined]
    (dotimes [index projection-samples]
      (let [progress (/ (ak/as :f32 (ak/floatFromInt index))
                        (ak/as :f32 (ak/floatFromInt projection-samples)))
            center (pose progress 0.0)]
        (set! (az/index points index)
              (az/array-init [:array 2 :f32] [(az/field center x) (az/field center y)]))))
    points))

(az/defconst projection-centers [:array projection-samples [:array 2 :f32]]
  (projection-centers-type))

(az/defn project
  "Project a world point onto the nearest point of the procedural centerline.
  A bounded coarse scan plus ten local refinements is deterministic,
  allocation-free, and accurate enough for checkpoints, recovery, and tools."
  :-
  Projection
  [[x :f32]
   [y :f32]]
  (let [sample-count
        (ak/as :f32 (ak/floatFromInt projection-samples))
        ^{:var true :zig/type :f32} best-progress 0.0
        ^{:var true :zig/type :f32} best-distance 1000.0]
    (dotimes [index projection-samples]
      (let [progress
            (/ (ak/as :f32 (ak/floatFromInt index)) sample-count)
            center (az/index projection-centers index)
            dx (- x (az/index center 0))
            dy (- y (az/index center 1))
            distance (+ (* dx dx) (* dy dy))]
        (when (< distance best-distance)
          (set! best-progress progress)
          (set! best-distance distance))))
    (let [^{:var true :zig/type :f32} step (/ 1.0 sample-count)]
      (dotimes [_ 10]
        (let [left (wrap-progress (- best-progress step))
              right (wrap-progress (+ best-progress step))
              left-distance (center-distance-squared x y left)
              right-distance (center-distance-squared x y right)]
          (when (< left-distance best-distance)
            (set! best-progress left)
            (set! best-distance left-distance))
          (when (< right-distance best-distance)
            (set! best-progress right)
            (set! best-distance right))
          (set! step (* step 0.5)))))
    (let [center (pose best-progress 0.0)
          dx (- x (az/field center x))
          dy (- y (az/field center y))
          normal-x (- (std-math/sin (az/field center heading)))
          normal-y (std-math/cos (az/field center heading))
          distance-squared (+ (* dx dx) (* dy dy))]
      (Projection
       {:progress best-progress
        :lane (* 20.0 (+ (* dx normal-x) (* dy normal-y)))
        :distance_squared distance-squared}))))

(az/defn distance-to-centerline-squared
  "Return only the nearest centerline distance for collision/recovery callers
  that do not need the full projection."
  :-
  :f32
  [[x :f32]
   [y :f32]]
  (az/field (project x y) distance_squared))
