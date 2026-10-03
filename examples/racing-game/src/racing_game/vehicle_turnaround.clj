(ns racing-game.vehicle-turnaround
  "Low-speed, body-relative turning after a spin. Outputs pedals and steering;
  never writes a rigid-body pose or velocity."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.track :as track]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]))

(a/defstruct State {:layout :extern}
  [[:active :bool] [:gear :i8] [:turn_sign :f32] [:lane_limit :f32]])

(a/defstruct Output {:layout :extern}
  [[:state State] [:control driver/Control]])

(a/defn heading :f32 [[body physics/BodyState]]
  (math/atan2 (* 2.0 (+ (* (a/field body qw) (a/field body qz))
                         (* (a/field body qx) (a/field body qy))))
    (- 1.0 (* 2.0 (+ (* (a/field body qy) (a/field body qy))
                      (* (a/field body qz) (a/field body qz)))))))

(a/defn angle :f32 [[value :f32]]
  (math/atan2 (math/sin value) (math/cos value)))

(a/defn static-overlap :bool
  "Read-only Box3D query callback. Dynamic cars are checked separately."
  {:attrs #{:export}} [[shape b3/b3ShapeId] [context [:optional [:* :anyopaque]]]]
  (if (ak/== (b3/b3Body_GetType (b3/b3Shape_GetBody shape)) b3/b3_staticBody)
    (do (ak/= (a/deref (a/cast context [:* :bool])) true) false)
    true))

(a/defn static-clearance :bool
  "Query the actual static collision meshes, including authored containment.
  The cloud encloses the car footprint; this never moves a simulation body." [[world b3/b3WorldId] [x :f32] [y :f32] [z :f32] [yaw :f32]]
  (let [^:var points (ak/as ak/undefined [:array 8 b3/b3Vec3])
        ^:var blocked (ak/bool false)]
    (dotimes [i 8]
      (let [local-x (if (ak/== (ak/& i 1) 0) (ak/as -2.55 :f32) 2.55)
            local-y (if (ak/== (ak/& i 2) 0) (ak/as -1.47 :f32) 1.47)]
        (ak/= (a/index points i)
          (b3/b3Vec3 {:x (- (* local-x (math/cos yaw)) (* local-y (math/sin yaw)))
                      :y (+ (* local-x (math/sin yaw)) (* local-y (math/cos yaw)))
                      :z (if (ak/== (ak/& i 4) 0) (ak/as -0.25 :f32) 0.25)}))))
    (let [proxy (b3/b3ShapeProxy {:points (ak/& points) :count 8 :radius 0.05})]
      (ak/= :_ (b3/b3World_OverlapShape world (b3/b3Pos {:x x :y y :z z})
                 (ak/& proxy) (b3/b3DefaultQueryFilter) (ak/& static-overlap) (ak/& blocked))))
    (ak/! blocked)))

(a/defn footprint-separation :f32
  "Signed separating-axis clearance between two conservative car footprints.
  Positive means separated; negative means overlap. Units are metres.
  Half-length is the 2.5m chassis plus 5cm; half-width encloses the authored
  0.99m axle offset and 0.48m-radius/width tires at maximum 0.45rad steering." [[x :f32] [y :f32] [yaw :f32] [other physics/BodyState]]
  (let [other-yaw (heading other)
        dx (- (a/field other x) x)
        dy (- (a/field other y) y)
        cosine (ak/abs (math/cos (- other-yaw yaw)))
        sine (ak/abs (math/sin (- other-yaw yaw)))
        long-span (+ 2.55 (* 2.55 cosine) (* 1.47 sine))
        short-span (+ 1.47 (* 2.55 sine) (* 1.47 cosine))]
    (ak/max
      (ak/max (- (ak/abs (+ (* dx (math/cos yaw)) (* dy (math/sin yaw)))) long-span)
              (- (ak/abs (- (* dy (math/cos yaw)) (* dx (math/sin yaw)))) short-span))
      (ak/max (- (ak/abs (+ (* dx (math/cos other-yaw)) (* dy (math/sin other-yaw)))) long-span)
              (- (ak/abs (- (* dy (math/cos other-yaw)) (* dx (math/sin other-yaw)))) short-span)))))

(a/defn moving-traffic-clear? :bool
  "Conservative swept clearance for traffic crossing the short recovery arc.
  Endpoint-only checks can miss a fast car that crosses between samples."
  [[body physics/BodyState] [other physics/BodyState]
   [x :f32] [y :f32] [seconds :f32]]
  (let [dx (- (a/field other x) (a/field body x))
        dy (- (a/field other y) (a/field body y))
        relative-x (- (* (a/field other vx) seconds) (- x (a/field body x)))
        relative-y (- (* (a/field other vy) seconds) (- y (a/field body y)))
        squared (+ (* relative-x relative-x) (* relative-y relative-y))
        fraction (driver/clamp-unit (/ (- (+ (* dx relative-x) (* dy relative-y)))
                                       (ak/max 0.00001 squared)))
        nearest-x (+ dx (* relative-x fraction))
        nearest-y (+ dy (* relative-y fraction))]
    (>= (+ (* nearest-x nearest-x) (* nearest-y nearest-y)) 36.0)))

(a/defn motion-pose [:array 3 :f32]
  "Read-only short-arc prediction in metres/radians for the requested wheel
  steering. Signed travel reverses yaw in reverse gear; steering does not.
  The result is a query position, never an imposed rigid-body transform."
  [[body physics/BodyState] [steering :f32] [distance :f32]]
  (let [yaw (heading body)
        wheelbase (- (a/index (a/index spec/wheel-geometry 0) 0)
                     (a/index (a/index spec/wheel-geometry 2) 0))
        curvature (/ (math/tan steering) wheelbase)
        next-yaw (+ yaw (* distance curvature))]
    (if (< (ak/abs curvature) 0.00001)
      (a/init [(+ (a/field body x) (* distance (math/cos yaw)))
         (+ (a/field body y) (* distance (math/sin yaw))) next-yaw] [:array 3 :f32])
      (a/init [(+ (a/field body x) (/ (- (math/sin next-yaw) (math/sin yaw)) curvature))
         (- (a/field body y) (/ (- (math/cos next-yaw) (math/cos yaw)) curvature))
         next-yaw] [:array 3 :f32]))))

(a/defn footprint-side-radius :f32
  "Road-normal half-span of the same oriented envelope used by the veto." [[yaw :f32] [road-yaw :f32]]
  (+ (* 2.55 (ak/abs (math/sin (- yaw road-yaw))))
     (* 1.47 (ak/abs (math/cos (- yaw road-yaw))))))

(a/defn recovery-side-clearance :f32
  "Required lateral centre gap before committing to a parallel passing lane.
  Include BOTH measured orientations and a 30cm planning margin, instead of
  declaring the obstacle cleared at a fixed 2.7m before tires fit beside it." [[body physics/BodyState] [other physics/BodyState] [road-yaw :f32]]
  (+ (footprint-side-radius (heading body) road-yaw)
     (footprint-side-radius (heading other) road-yaw) 0.3))

(a/defn separation-safe? :bool
  "Keep an existing positive clearance instead of demanding that every 5cm
  forward sample widen it by 2cm. Already-overlapping conservative envelopes
  must actually separate. One millimetre covers metre-space float roundoff;
  it never permits a new predicted overlap from a separated starting pose." [[current :f32] [predicted :f32]]
  (cond
    (>= predicted 0.2) true
    (>= current 0.0) (and (>= predicted 0.0) (>= predicted (- current 0.001)))
    :else (> predicted (+ current 0.001))))

(a/defn corridor-step-safe? :bool
  "Keep the anchored corridor fixed. If a collision has pushed a car outside
  it, permit only measured inward progress, never parallel/outward ratcheting.
  This is a query gate, not a body transform or a waiver of collision checks." [[current-lane :f32] [predicted-lane :f32] [limit :f32]]
  (and (math/isFinite current-lane) (math/isFinite predicted-lane)
       (math/isFinite limit) (>= limit 0.0)
       (if (<= (ak/abs current-lane) limit)
         (<= (ak/abs predicted-lane) limit)
         (< (ak/abs predicted-lane) (- (ak/abs current-lane) 0.001)))))

(a/defn motion-clearance-reasons :u8
  "Predict a short steering arc, including the rectangular chassis footprint.
  This is only a pedal planner: Box3D remains authoritative for actual motion.
  Includes eight measured car centres; self is excluded by index.
  Returns a bit mask: 1 ground, 2 corridor, 4 static mesh, 8 footprint,
  16 crossing traffic, 32 unspecified gear. Zero means this arc is clear.
  Takes actual steering radians, including straight-ahead motion."
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [steering :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (when (ak/== gear 0)
    (ak/return (ak/as 32 :u8)))
  (let [yaw (heading body)
        current-projection (track/project (* (a/field body x) 0.001)
                                           (* (a/field body y) 0.001))
        current-lane (* 50.0 (a/field current-projection lane))
        direction (ak/as (ak/floatFromInt gear) :f32)
        ^:var reasons (ak/u8 0)]
    (dotimes [i 4]
      (let [distance (* direction (+ 0.05 (* 0.1 (ak/as (ak/floatFromInt i) :f32))))
            predicted (motion-pose body steering distance)
            predicted-yaw (a/index predicted 2)
            x (a/index predicted 0)
            y (a/index predicted 1)
            projection (track/project (* x 0.001) (* y 0.001))
            road (circuit/at-distance (* 4309.0 (a/field projection progress)) 0.0)
            half-width (footprint-side-radius predicted-yaw (a/field road heading))]
        ;; Ground support extends to +/-35m. Wall clearance is queried from
        ;; the real static mesh, not approximated by an arbitrary road margin.
        (let [envelope (+ (ak/abs (* 50.0 (a/field projection lane))) half-width)]
          (when (> envelope 33.0)
            (ak/= reasons (ak/| reasons 1)))
          (when (ak/! (corridor-step-safe? current-lane
                         (* 50.0 (a/field projection lane)) lane-limit))
            (ak/= reasons (ak/| reasons 2)))
          (when (ak/! (static-clearance world x y (+ (a/field road z) 0.76) predicted-yaw))
            (ak/= reasons (ak/| reasons 4))))
        (dotimes [j count]
          (when (ak/!= j self)
            (let [other (a/index others j)
                  seconds (/ (ak/abs distance) 0.8)
                  ^:var anticipated other]
              ;; Predict nearby traffic, never change its actual physics pose.
              (ak/= (a/field anticipated x) (+ (a/field other x) (* (a/field other vx) seconds)))
              (ak/= (a/field anticipated y) (+ (a/field other y) (* (a/field other vy) seconds)))
              (let [gap (footprint-separation x y predicted-yaw anticipated)
                    current-gap (footprint-separation (a/field body x) (a/field body y) yaw other)
                    traffic-speed-squared (+ (* (a/field other vx) (a/field other vx))
                                             (* (a/field other vy) (a/field other vy)))]
                ;; Existing close contact may only be separated, never approached.
                (when (< (ak/abs (- (a/field other z) (a/field body z))) 3.0)
                  (when (ak/! (separation-safe? current-gap gap))
                    (ak/= reasons (ak/| reasons 8)))
                  (when (and (> traffic-speed-squared 4.0)
                             (ak/! (moving-traffic-clear? body other x y seconds)))
                    (ak/= reasons (ak/| reasons 16))))))))))
    reasons))

(a/defn clearance-reasons :u8
  "Turnaround candidates target a yaw direction, not a steering direction.
  Match its actual pedal controller: reverse gear also reverses steering."
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [sign :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (if (ak/== sign 0.0) (ak/as 32 :u8)
    (motion-clearance-reasons body others count self gear
      (* sign 0.45 (ak/as (ak/floatFromInt gear) :f32)) world lane-limit)))

(a/defn guard-recovery-control driver/Control
  "A low-speed safety veto, not a tactical decision. Check the final requested
  steering against every measured car and static obstacle, including wrecks.
  When blocked, brake without changing the intent, gear or any body state."
  [[control driver/Control] [body physics/BodyState]
   [others [:array protocol/racer-count physics/BodyState]] [count :usize] [self :usize]
   [gear :i8] [world b3/b3WorldId] [lane-limit :f32]]
  (let [^:var safe control]
    (when (ak/!= (motion-clearance-reasons body others count self gear
                    (a/field control steering) world lane-limit) 0)
      (ak/= (a/field safe throttle) 0.0)
      (ak/= (a/field safe brake) 1.0))
    safe))

(a/defn clearance :bool
  "Whether the physical and traffic constraints permit this low-speed arc."
  [[body physics/BodyState] [others [:array protocol/racer-count physics/BodyState]]
   [count :usize] [self :usize] [gear :i8] [sign :f32] [world b3/b3WorldId]
   [lane-limit :f32]]
  (ak/== (clearance-reasons body others count self gear sign world lane-limit) 0))

(a/defn step Output
  "Keep turning direction unless both arcs are blocked and the car is stopped.
  Switch between forward/reverse only after braking below 0.05m/s. Disabled control
  brakes an active turnaround and returns normal control only once stationary."
  [[previous State] [normal driver/Control] [body physics/BodyState]
   [others [:array protocol/racer-count physics/BodyState]] [count :usize] [self :usize] [enabled :bool]
   [world b3/b3WorldId]]
  (let [^:var state previous
        ^:var control normal
        road (circuit/at-distance (* 4309.0 (a/field normal progress)) 0.0)
        heading-error (angle (- (a/field road heading) (heading body)))
        speed (a/field normal speed)
        up (- 1.0 (* 2.0 (+ (* (a/field body qx) (a/field body qx))
                            (* (a/field body qy) (a/field body qy)))))
        safe (and enabled (> up 0.8))]
    (when (and safe (ak/! (a/field state active)) (> (ak/abs heading-error) 1.3))
      (ak/= (a/field state active) true)
      (ak/= (a/field state gear) 0)
      ;; Fix the corridor at entry so successive replans cannot ratchet the
      ;; vehicle farther into runoff. Allow limited room around a shoulder
      ;; starting point; the actual wall query remains an independent gate.
      (ak/= (a/field state lane_limit)
        (if (> (ak/abs (a/field normal lane)) 7.0)
          (+ (ak/abs (a/field normal lane)) 1.5)
          (ak/as 7.2 :f32)))
      (ak/= (a/field state turn_sign) (if (> heading-error 0.0) (ak/as 1.0 :f32) -1.0)))
    (when (a/field state active)
      (let [^:var forward (and safe (clearance body others count self 1 (a/field state turn_sign) world (a/field state lane_limit)))
            ^:var backward (and safe (clearance body others count self -1 (a/field state turn_sign) world (a/field state lane_limit)))
            current-clear (if (> (a/field state gear) 0) forward backward)]
        ;; Shortest yaw is not always the feasible turn at a wall. Search the
        ;; other pair of steering arcs before deciding neither gear is safe.
        ;; Never change the selected arc while the vehicle is still moving.
        (when (and safe (< speed 0.05) (ak/! forward) (ak/! backward))
          (let [other-sign (- (a/field state turn_sign))
                other-forward (clearance body others count self 1 other-sign world (a/field state lane_limit))
                other-backward (clearance body others count self -1 other-sign world (a/field state lane_limit))]
            (when (or other-forward other-backward)
              (ak/= (a/field state turn_sign) other-sign)
              (ak/= forward other-forward)
              (ak/= backward other-backward))))
        (ak/= (a/field control throttle) 0.0)
        (ak/= (a/field control brake) 1.0)
        (ak/= (a/field control steering) 0.0)
        (when (< speed 0.05)
          (cond
            (or (ak/! safe) (< (ak/abs heading-error) 0.20))
            (do (ak/= (a/field state active) false)
                (ak/= (a/field state gear) 0))

            (or (ak/== (a/field state gear) 0) (ak/! current-clear))
            (ak/= (a/field state gear)
                  (if forward (ak/as 1 :i8) (if backward (ak/as -1 :i8) 0)))))
        (when (and (a/field state active) safe
                   (ak/!= (a/field state gear) 0)
                   (> (ak/abs heading-error) 0.20)
                   (if (> (a/field state gear) 0) forward backward))
          (ak/= (a/field control steering)
                (* 0.45 (a/field state turn_sign) (ak/as (ak/floatFromInt (a/field state gear)) :f32)))
          (ak/= (a/field control throttle) (driver/clamp-unit (* (- 0.8 speed) 0.3)))
          (ak/= (a/field control brake) (driver/clamp-unit (* (- speed 0.8) 0.5))))))
    (Output {:state state :control control})))
