(ns racing-game.track
  "One hot-reloadable equation shared by native race physics and line rendering."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]))

(az/defconst tau :f32 6.2831855)

(az/defconst projection-samples :usize 192)

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
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[progress :f32]]
  (- progress (std-math/floor progress)))

(az/defn pose
  "Sample the medium-complex closed circuit. Lane is signed clip-space offset."
  {:attrs #{:public :implicit-return}}
  :-
  Pose
  [[progress :f32]
   [lane :f32]]
  (let [angle (- (* progress tau) 1.5707963)
        radius-x (+ 0.62
                    (* 0.12 (std-math/cos (* angle 3.0)))
                    (* 0.04 (std-math/sin (* angle 5.0))))
        radius-y (+ 0.42 (* 0.08 (std-math/sin (* angle 2.0))))
        radius-x-derivative
        (+ (* -0.36 (std-math/sin (* angle 3.0)))
           (* 0.20 (std-math/cos (* angle 5.0))))
        radius-y-derivative (* 0.16 (std-math/cos (* angle 2.0)))
        center-x (* (std-math/cos angle) radius-x)
        center-y (* (std-math/sin angle) radius-y)
        tangent-x (+ (* (- (std-math/sin angle)) radius-x)
                     (* (std-math/cos angle) radius-x-derivative))
        tangent-y (+ (* (std-math/cos angle) radius-y)
                     (* (std-math/sin angle) radius-y-derivative))
        tangent-length
        (ak/max 0.00001
                (std-math/sqrt (+ (* tangent-x tangent-x)
                                  (* tangent-y tangent-y))))
        normal-x (/ (- tangent-y) tangent-length)
        normal-y (/ tangent-x tangent-length)]
    (Pose {:x (+ center-x (* normal-x lane))
           :y (+ center-y (* normal-y lane))
           :heading (std-math/atan2 tangent-y tangent-x)})))

(az/defn center-distance-squared
  {:export false :implicit-return true}
  :-
  :f32
  [[x :f32]
   [y :f32]
   [progress :f32]]
  (let [center (pose progress 0.0)
        dx (- x (az/field center x))
        dy (- y (az/field center y))]
    (+ (* dx dx) (* dy dy))))

(az/defn project
  "Project a world point onto the nearest point of the procedural centerline.
  A bounded coarse scan plus ten local refinements is deterministic,
  allocation-free, and accurate enough for checkpoints, recovery, and tools."
  {:attrs #{:public :implicit-return}}
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
            distance (center-distance-squared x y progress)]
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
        :lane (+ (* dx normal-x) (* dy normal-y))
        :distance_squared distance-squared}))))

(az/defn distance-to-centerline-squared
  "Return only the nearest centerline distance for collision/recovery callers
  that do not need the full projection."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[x :f32]
   [y :f32]]
  (az/field (project x y) distance_squared))
