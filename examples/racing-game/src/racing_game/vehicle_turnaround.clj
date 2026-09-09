(ns racing-game.vehicle-turnaround
  "Low-speed, body-relative turning after a spin. Outputs pedals and steering;
  never writes a rigid-body pose or velocity."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.track :as track]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]))

(az/defstruct State {:layout :extern}
  [[:active :bool] [:gear :i8] [:turn_sign :f32] [:lane_limit :f32]])

(az/defstruct Output {:layout :extern}
  [[:state State] [:control driver/Control]])

(az/defn heading :- :f32 [[body physics/BodyState]]
  (math/atan2 (* 2.0 (+ (* (az/field body qw) (az/field body qz))
                         (* (az/field body qx) (az/field body qy))))
    (- 1.0 (* 2.0 (+ (* (az/field body qy) (az/field body qy))
                      (* (az/field body qz) (az/field body qz)))))))

(az/defn angle :- :f32 [[value :f32]]
  (math/atan2 (math/sin value) (math/cos value)))

(az/defn static-overlap
  "Read-only Box3D query callback. Dynamic cars are checked separately."
  {:attrs #{:export}}
  :- :bool [[shape b3/b3ShapeId] [context [:optional [:* :anyopaque]]]]
  (if (ak/== (b3/b3Body_GetType (b3/b3Shape_GetBody shape)) b3/b3_staticBody)
    (do (set! (az/deref (az/cast context [:* :bool])) true) false)
    true))

(az/defn static-clearance
  "Query the actual static collision meshes, including authored containment.
  The cloud encloses the car footprint; this never moves a simulation body."
  :- :bool [[world b3/b3WorldId] [x :f32] [y :f32] [z :f32] [yaw :f32]]
  (let [^{:var [:array 8 b3/b3Vec3]} points ak/undefined
        ^{:var :bool} blocked false]
    (dotimes [i 8]
      (let [local-x (if (ak/== (ak/& i 1) 0) (ak/as :f32 -2.55) 2.55)
            local-y (if (ak/== (ak/& i 2) 0) (ak/as :f32 -1.47) 1.47)]
        (set! (az/index points i)
          (b3/b3Vec3 {:x (- (* local-x (math/cos yaw)) (* local-y (math/sin yaw)))
                      :y (+ (* local-x (math/sin yaw)) (* local-y (math/cos yaw)))
                      :z (if (ak/== (ak/& i 4) 0) (ak/as :f32 -0.25) 0.25)}))))
    (let [proxy (b3/b3ShapeProxy {:points (ak/& points) :count 8 :radius 0.05})]
      (set! _ (b3/b3World_OverlapShape world (b3/b3Pos {:x x :y y :z z})
                 (ak/& proxy) (b3/b3DefaultQueryFilter) (ak/& static-overlap) (ak/& blocked))))
    (ak/! blocked)))

(az/defn footprint-separation
  "Signed separating-axis clearance between two conservative car footprints.
  Positive means separated; negative means overlap. Units are metres.
  Half-length is the 2.5m chassis plus 5cm; half-width encloses the authored
  0.99m axle offset and 0.48m-radius/width tires at maximum 0.45rad steering."
  :- :f32 [[x :f32] [y :f32] [yaw :f32] [other physics/BodyState]]
  (let [other-yaw (heading other)
        dx (- (az/field other x) x)
        dy (- (az/field other y) y)
        cosine (ak/abs (math/cos (- other-yaw yaw)))
        sine (ak/abs (math/sin (- other-yaw yaw)))
        long-span (+ 2.55 (* 2.55 cosine) (* 1.47 sine))
        short-span (+ 1.47 (* 2.55 sine) (* 1.47 cosine))]
    (ak/max
      (ak/max (- (ak/abs (+ (* dx (math/cos yaw)) (* dy (math/sin yaw)))) long-span)
              (- (ak/abs (- (* dy (math/cos yaw)) (* dx (math/sin yaw)))) short-span))
      (ak/max (- (ak/abs (+ (* dx (math/cos other-yaw)) (* dy (math/sin other-yaw)))) long-span)
              (- (ak/abs (- (* dy (math/cos other-yaw)) (* dx (math/sin other-yaw)))) short-span)))))

(az/defn moving-traffic-clear?
  "Conservative swept clearance for traffic crossing the short recovery arc.
  Endpoint-only checks can miss a fast car that crosses between samples."
  :- :bool
  [[body physics/BodyState] [other physics/BodyState]
   [x :f32] [y :f32] [seconds :f32]]
  (let [dx (- (az/field other x) (az/field body x))
        dy (- (az/field other y) (az/field body y))
        relative-x (- (* (az/field other vx) seconds) (- x (az/field body x)))
        relative-y (- (* (az/field other vy) seconds) (- y (az/field body y)))
        squared (+ (* relative-x relative-x) (* relative-y relative-y))
        fraction (driver/clamp-unit (/ (- (+ (* dx relative-x) (* dy relative-y)))
                                       (ak/max 0.00001 squared)))
        nearest-x (+ dx (* relative-x fraction))
        nearest-y (+ dy (* relative-y fraction))]
    (>= (+ (* nearest-x nearest-x) (* nearest-y nearest-y)) 36.0)))

(az/defn motion-pose
  "Read-only short-arc prediction in metres/radians for the requested wheel
  steering. Signed travel reverses yaw in reverse gear; steering does not.
  The result is a query position, never an imposed rigid-body transform."
  :- [:array 3 :f32]
  [[body physics/BodyState] [steering :f32] [distance :f32]]
  (let [yaw (heading body)
        wheelbase (- (az/index (az/index spec/wheel-geometry 0) 0)
                     (az/index (az/index spec/wheel-geometry 2) 0))
        curvature (/ (math/tan steering) wheelbase)
        next-yaw (+ yaw (* distance curvature))]
    (if (< (ak/abs curvature) 0.00001)
      (az/array-init [:array 3 :f32]
        [(+ (az/field body x) (* distance (math/cos yaw)))
         (+ (az/field body y) (* distance (math/sin yaw))) next-yaw])
      (az/array-init [:array 3 :f32]
        [(+ (az/field body x) (/ (- (math/sin next-yaw) (math/sin yaw)) curvature))
         (- (az/field body y) (/ (- (math/cos next-yaw) (math/cos yaw)) curvature))
         next-yaw]))))

(az/defn footprint-side-radius
  "Road-normal half-span of the same oriented envelope used by the veto."
  :- :f32 [[yaw :f32] [road-yaw :f32]]
  (+ (* 2.55 (ak/abs (math/sin (- yaw road-yaw))))
     (* 1.47 (ak/abs (math/cos (- yaw road-yaw))))))

(az/defn recovery-side-clearance
  "Required lateral centre gap before committing to a parallel passing lane.
  Include BOTH measured orientations and a 30cm planning margin, instead of
  declaring the obstacle cleared at a fixed 2.7m before tires fit beside it."
  :- :f32 [[body physics/BodyState] [other physics/BodyState] [road-yaw :f32]]
  (+ (footprint-side-radius (heading body) road-yaw)
     (footprint-side-radius (heading other) road-yaw) 0.3))

(az/defn separation-safe?
  "Keep an existing positive clearance instead of demanding that every 5cm
  forward sample widen it by 2cm. Already-overlapping conservative envelopes
  must actually separate. One millimetre covers metre-space float roundoff;
  it never permits a new predicted overlap from a separated starting pose."
  :- :bool [[current :f32] [predicted :f32]]
  (cond
    (>= predicted 0.2) true
    (>= current 0.0) (and (>= predicted 0.0) (>= predicted (- current 0.001)))
    :else (> predicted (+ current 0.001))))

(az/defn corridor-step-safe?
  "Keep the anchored corridor fixed. If a collision has pushed a car outside
  it, permit only measured inward progress, never parallel/outward ratcheting.
  This is a query gate, not a body transform or a waiver of collision checks."
  :- :bool [[current-lane :f32] [predicted-lane :f32] [limit :f32]]
  (and (math/isFinite current-lane) (math/isFinite predicted-lane)
       (math/isFinite limit) (>= limit 0.0)
       (if (<= (ak/abs current-lane) limit)
         (<= (ak/abs predicted-lane) limit)
         (< (ak/abs predicted-lane) (- (ak/abs current-lane) 0.001)))))

(az/defn motion-clearance-reasons
  "Predict a short steering arc, including the rectangular chassis footprint.
  This is only a pedal planner: Box3D remains authoritative for actual motion.
  Includes eight measured car centres; self is excluded by index.
  Returns a bit mask: 1 ground, 2 corridor, 4 static mesh, 8 footprint,
  16 crossing traffic, 32 unspecified gear. Zero means this arc is clear.
  Takes actual steering radians, including straight-ahead motion."
  :- :u8
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [steering :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (when (ak/== gear 0)
    (ak/return (ak/as :u8 32)))
  (let [yaw (heading body)
        current-projection (track/project (* (az/field body x) 0.001)
                                           (* (az/field body y) 0.001))
        current-lane (* 50.0 (az/field current-projection lane))
        direction (ak/as :f32 (ak/floatFromInt gear))
        ^{:var :u8} reasons 0]
    (dotimes [i 4]
      (let [distance (* direction (+ 0.05 (* 0.1 (ak/as :f32 (ak/floatFromInt i)))))
            predicted (motion-pose body steering distance)
            predicted-yaw (az/index predicted 2)
            x (az/index predicted 0)
            y (az/index predicted 1)
            projection (track/project (* x 0.001) (* y 0.001))
            road (circuit/at-distance (* 4309.0 (az/field projection progress)) 0.0)
            half-width (footprint-side-radius predicted-yaw (az/field road heading))]
        ;; Ground support extends to +/-35m. Wall clearance is queried from
        ;; the real static mesh, not approximated by an arbitrary road margin.
        (let [envelope (+ (ak/abs (* 50.0 (az/field projection lane))) half-width)]
          (when (> envelope 33.0)
            (set! reasons (ak/| reasons 1)))
          (when (ak/! (corridor-step-safe? current-lane
                         (* 50.0 (az/field projection lane)) lane-limit))
            (set! reasons (ak/| reasons 2)))
          (when (ak/! (static-clearance world x y (+ (az/field road z) 0.76) predicted-yaw))
            (set! reasons (ak/| reasons 4))))
        (dotimes [j count]
          (when (ak/!= j self)
            (let [other (az/index others j)
                  seconds (/ (ak/abs distance) 0.8)
                  ^:var anticipated other]
              ;; Predict nearby traffic, never change its actual physics pose.
              (set! (az/field anticipated x) (+ (az/field other x) (* (az/field other vx) seconds)))
              (set! (az/field anticipated y) (+ (az/field other y) (* (az/field other vy) seconds)))
              (let [gap (footprint-separation x y predicted-yaw anticipated)
                    current-gap (footprint-separation (az/field body x) (az/field body y) yaw other)
                    traffic-speed-squared (+ (* (az/field other vx) (az/field other vx))
                                             (* (az/field other vy) (az/field other vy)))]
                ;; Existing close contact may only be separated, never approached.
                (when (< (ak/abs (- (az/field other z) (az/field body z))) 3.0)
                  (when (ak/! (separation-safe? current-gap gap))
                    (set! reasons (ak/| reasons 8)))
                  (when (and (> traffic-speed-squared 4.0)
                             (ak/! (moving-traffic-clear? body other x y seconds)))
                    (set! reasons (ak/| reasons 16))))))))))
    reasons))

(az/defn clearance-reasons
  "Turnaround candidates target a yaw direction, not a steering direction.
  Match its actual pedal controller: reverse gear also reverses steering."
  :- :u8
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [sign :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (if (ak/== sign 0.0) (ak/as :u8 32)
    (motion-clearance-reasons body others count self gear
      (* sign 0.45 (ak/as :f32 (ak/floatFromInt gear))) world lane-limit)))

(az/defn guard-recovery-control
  "A low-speed safety veto, not a tactical decision. Check the final requested
  steering against every measured car and static obstacle, including wrecks.
  When blocked, brake without changing the intent, gear or any body state."
  :- driver/Control
  [[control driver/Control] [body physics/BodyState]
   [others [:array protocol/racer-count physics/BodyState]] [count :usize] [self :usize]
   [gear :i8] [world b3/b3WorldId] [lane-limit :f32]]
  (let [^:var safe control]
    (when (ak/!= (motion-clearance-reasons body others count self gear
                    (az/field control steering) world lane-limit) 0)
      (set! (az/field safe throttle) 0.0)
      (set! (az/field safe brake) 1.0))
    safe))

(az/defn clearance
  "Whether the physical and traffic constraints permit this low-speed arc."
  :- :bool
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [sign :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (ak/== (clearance-reasons body others count self gear sign world lane-limit) 0))

(az/defn step
  "Keep turning direction unless both arcs are blocked and the car is stopped.
  Switch between forward/reverse only after braking below 0.05m/s. Disabled control
  brakes an active turnaround and returns normal control only once stationary."
  :- Output
  [[previous State] [normal driver/Control] [body physics/BodyState]
   [others [:array protocol/racer-count physics/BodyState]] [count :usize] [self :usize] [enabled :bool]
   [world b3/b3WorldId]]
  (let [^:var state previous
        ^:var control normal
        road (circuit/at-distance (* 4309.0 (az/field normal progress)) 0.0)
        heading-error (angle (- (az/field road heading) (heading body)))
        speed (az/field normal speed)
        up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                            (* (az/field body qy) (az/field body qy)))))
        safe (and enabled (> up 0.8))]
    (when (and safe (ak/! (az/field state active)) (> (ak/abs heading-error) 1.3))
      (set! (az/field state active) true)
      (set! (az/field state gear) 0)
      ;; Fix the corridor at entry so successive replans cannot ratchet the
      ;; vehicle farther into runoff. Allow limited room around a shoulder
      ;; starting point; the actual wall query remains an independent gate.
      (set! (az/field state lane_limit)
        (if (> (ak/abs (az/field normal lane)) 7.0)
          (+ (ak/abs (az/field normal lane)) 1.5)
          (ak/as :f32 7.2)))
      (set! (az/field state turn_sign) (if (> heading-error 0.0) (ak/as :f32 1.0) -1.0)))
    (when (az/field state active)
      (let [^:var forward (and safe (clearance body others count self 1 (az/field state turn_sign) world (az/field state lane_limit)))
            ^:var backward (and safe (clearance body others count self -1 (az/field state turn_sign) world (az/field state lane_limit)))
            current-clear (if (> (az/field state gear) 0) forward backward)]
        ;; Shortest yaw is not always the feasible turn at a wall. Search the
        ;; other pair of steering arcs before deciding neither gear is safe.
        ;; Never change the selected arc while the vehicle is still moving.
        (when (and safe (< speed 0.05) (ak/! forward) (ak/! backward))
          (let [other-sign (- (az/field state turn_sign))
                other-forward (clearance body others count self 1 other-sign world (az/field state lane_limit))
                other-backward (clearance body others count self -1 other-sign world (az/field state lane_limit))]
            (when (or other-forward other-backward)
              (set! (az/field state turn_sign) other-sign)
              (set! forward other-forward)
              (set! backward other-backward))))
        (set! (az/field control throttle) 0.0)
        (set! (az/field control brake) 1.0)
        (set! (az/field control steering) 0.0)
        (when (< speed 0.05)
          (cond
            (or (ak/! safe) (< (ak/abs heading-error) 0.20))
            (do (set! (az/field state active) false)
                (set! (az/field state gear) 0))

            (or (ak/== (az/field state gear) 0) (ak/! current-clear))
            (set! (az/field state gear)
                  (if forward (ak/as :i8 1) (if backward (ak/as :i8 -1) 0)))))
        (when (and (az/field state active) safe
                   (ak/!= (az/field state gear) 0)
                   (> (ak/abs heading-error) 0.20)
                   (if (> (az/field state gear) 0) forward backward))
          (set! (az/field control steering)
                (* 0.45 (az/field state turn_sign) (ak/as :f32 (ak/floatFromInt (az/field state gear)))))
          (set! (az/field control throttle) (driver/clamp-unit (* (- 0.8 speed) 0.3)))
          (set! (az/field control brake) (driver/clamp-unit (* (- speed 0.8) 0.5))))))
    (Output {:state state :control control})))
