(ns racing-game.track
  "One hot-reloadable equation shared by native race physics and line rendering."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]))

(az/defconst tau :f32 6.2831855)

(az/defstruct Pose
  "Centerline position, lane offset, and forward heading at normalized progress."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:heading :f32]])

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
