(ns racing-game.vehicle-driver
  "Low-level path following translates AI intent into pedals and steering.
  It never writes a body position, rotation, or velocity."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [racing-game.physics :as physics]
            [racing-game.track :as track]
            [racing-game.circuit :as circuit]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]))

(az/defstruct Control {:layout :extern}
  [[:throttle :f32] [:brake :f32] [:steering :f32]
   [:progress :f32] [:lane :f32] [:speed :f32]])

(az/defn clamp-unit :- :f32 [[value :f32]]
  (ak/max 0.0 (ak/min 1.0 value)))

(az/defn overturned?
  "Body orientation only, independent of race classification. Match the
  retirement observer's support threshold, without its time/finish policy."
  :- :bool [[body physics/BodyState]]
  (< (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                       (* (az/field body qy) (az/field body qy))))) 0.2))

(az/defn stop-if-overturned
  "Final pedal safety gate, also during cooldown. Never changes body pose,
  velocity, classification or the original AI intent. An upright car keeps
  its ordinary controls; the next tick can resume after a physical recovery."
  :- Control [[control Control] [body physics/BodyState]]
  (let [^:var result control]
    (when (overturned? body)
      (set! (az/field result throttle) 0.0)
      (set! (az/field result brake) 1.0)
      (set! (az/field result steering) 0.0))
    result))

(az/defn braking-envelope
  "Plan using a tire-force budget, including braking distance. Sampling every
  five metres catches tight bends missed by the old 25m/3g kinematic envelope.
  This controls pedals; exceeding the budget still causes real loss of grip."
  :- :f32 [[distance :f32] [speed :f32]]
  (let [^{:var :f32} limit 100.0
        ;; Box3D combines asphalt (1.0) and tire friction geometrically.
        ;; Reserve force for tracking, axle load transfer and combined braking.
        grip (* 0.60 (ak/sqrt physics/tire-friction))
        mass (+ physics/chassis-mass-kg (* 4.0 physics/wheel-mass-kg))
        ;; Horizon covers stopping distance at the 14m/s² planning budget,
        ;; plus 25m. The actual brakes remain torque/contact limited.
        samples (ak/as :usize (ak/intFromFloat
                  (ak/min 181.0 (ak/max 41.0 (+ 5.0 (/ (* speed speed) 140.0))))))]
    (dotimes [i samples]
      (let [ahead (* (ak/as :f32 (ak/floatFromInt i)) 5.0)
            a (circuit/at-distance (- (+ distance ahead) 6.0) 0.0)
            b (circuit/at-distance (+ distance ahead 6.0) 0.0)
            delta (- (az/field b heading) (az/field a heading))
            curvature (/ (ak/abs (math/atan2 (math/sin delta) (math/cos delta))) 12.0)
            ;; m*v²*k <= mu*(m*g + aero*v²), solved for v².
            ;; This is a pedal plan, NOT a lateral force or a velocity setter.
            corner-speed-squared (/ (* grip 9.81)
                                      (ak/max 0.00001
                                        (- curvature (/ (* grip physics/downforce-coefficient) mass))))]
        (set! limit (ak/min limit (ak/sqrt (+ corner-speed-squared (* 28.0 ahead)))))))
    limit))

(az/defn follow-route
  "AI supplies desired m/s and lane metres. Ground-relative position/ranking
  are measured from the body, never imposed on it. Pure pursuit produces a
  steering angle; feedback produces throttle/brake, applied through tires."
  :- Control [[car physics/Vehicle] [desired-speed :f32] [lane-metres :f32] [pit? :bool]]
  (let [body (az/field car chassis)
        state (physics/body-state body)
        projection (track/project (* (az/field state x) 0.001) (* (az/field state y) 0.001))
        speed (ak/sqrt (+ (* (az/field state vx) (az/field state vx))
                          (* (az/field state vy) (az/field state vy))))
        ahead (+ 6.0 (* speed 0.16))
        target-distance (+ (* (az/field projection progress) 4309.0) ahead)
        offset (if pit? (track/pit-offset (/ target-distance 4309.0) lane-metres)
                        (ak/max -4.5 (ak/min 4.5 lane-metres)))
        target (circuit/at-distance target-distance offset)
        delta (b3/b3InvRotateVector (b3/b3Body_GetRotation body)
                (b3/b3Vec3 {:x (- (az/field target x) (az/field state x))
                            :y (- (az/field target y) (az/field state y))
                            :z 0.0}))
        wheelbase (- (az/index (az/index spec/wheel-geometry 0) 0)
                      (az/index (az/index spec/wheel-geometry 2) 0))
        steering (math/atan2 (* 2.0 wheelbase (az/field delta y))
                             (ak/max 1.0 (+ (* (az/field delta x) (az/field delta x))
                                            (* (az/field delta y) (az/field delta y)))))
        safe-speed (braking-envelope (* (az/field projection progress) 4309.0) speed)
        speed-error (- (ak/min desired-speed safe-speed) speed)]
    (Control {:throttle (clamp-unit (* speed-error 0.3))
               :brake (clamp-unit (* speed-error -0.18))
               :steering (ak/max -0.45 (ak/min 0.45 steering))
               :progress (az/field projection progress)
               :lane (* (az/field projection lane) 50.0) :speed speed})))

(az/defn follow :- Control [[car physics/Vehicle] [desired-speed :f32] [lane-metres :f32]]
  (follow-route car desired-speed lane-metres false))

(az/defstruct LanePlan
  "Optional, caller-owned continuous lateral path; no native body ownership."
  [[:initialized :bool] [:active :bool] [:target :f32]
   [:origin :f32] [:length :f32] [:coefficients [:array 6 :f32]]])

(az/defn lane-plan-state
  "Lateral position and its first two distance derivatives. Evaluating this
  path never modifies vehicle position or velocity."
  :- [:array 3 :f32] [[plan [:* LanePlan]] [distance :f32]]
  (if (ak/! (az/field plan active))
    (az/array-init [:array 3 :f32] [(az/field plan target) 0.0 0.0])
    (let [delta (- distance (az/field plan origin))
          travelled (cond (< delta -2154.5) (+ delta 4309.0)
                          (> delta 2154.5) (- delta 4309.0) :else delta)
          length (az/field plan length)
          u (clamp-unit (/ travelled length))
          c (az/field plan coefficients)
          position (+ (az/index c 0) (* u (+ (az/index c 1) (* u (+ (az/index c 2)
                     (* u (+ (az/index c 3) (* u (+ (az/index c 4) (* u (az/index c 5)))))))))))
          first (/ (+ (az/index c 1) (* u (+ (* 2.0 (az/index c 2))
                      (* u (+ (* 3.0 (az/index c 3)) (* u (+ (* 4.0 (az/index c 4))
                      (* u 5.0 (az/index c 5))))))))) length)
          second (/ (+ (* 2.0 (az/index c 2)) (* u (+ (* 6.0 (az/index c 3))
                       (* u (+ (* 12.0 (az/index c 4)) (* u 20.0 (az/index c 5)))))))
                    (* length length))]
      (az/array-init [:array 3 :f32]
        [position (if (< u 1.0) first 0.0) (if (< u 1.0) second 0.0)]))))

(az/defn update-lane-plan!
  "Plan a quintic lane transition. A new request preserves the previous
  path's position/slope/curvature. Once completed, it cannot repeat next lap.
  The 3m/s² lane-change budget is driver planning, not an applied force."
  :- :void [[plan [:* LanePlan]] [distance :f32] [measured-lane :f32]
            [speed :f32] [requested-lane :f32]]
  (when (ak/! (az/field plan initialized))
    (set! (az/field plan initialized) true)
    (set! (az/field plan active) false)
    (set! (az/field plan target) measured-lane))
  (when (az/field plan active)
    (let [delta (- distance (az/field plan origin))
          travelled (if (< delta -2154.5) (+ delta 4309.0) delta)]
      (when (>= travelled (az/field plan length))
        (set! (az/field plan active) false))))
  (let [target (ak/max -4.5 (ak/min 4.5 requested-lane))]
    (when (> (ak/abs (- target (az/field plan target))) 0.0001)
      (let [previous (lane-plan-state plan distance)
            displacement (- target (az/index previous 0))
            duration (ak/max 1.5 (ak/sqrt (/ (* 5.773503 (ak/abs displacement)) 3.0)))
            length (ak/max 12.0 (* (ak/max speed 5.0) duration))
            slope (* (az/index previous 1) length)
            curvature (* 0.5 (az/index previous 2) length length)]
        (set! (az/field plan coefficients)
          (az/array-init [:array 6 :f32]
            [(az/index previous 0) slope curvature
             (- (* 10.0 displacement) (* 6.0 slope) (* 3.0 curvature))
             (+ (* -15.0 displacement) (* 8.0 slope) (* 3.0 curvature))
             (- (* 6.0 displacement) (* 3.0 slope) curvature)]))
        (set! (az/field plan origin) distance)
        (set! (az/field plan length) length)
        (set! (az/field plan target) target)
        (set! (az/field plan active) true)))))

(az/defn follow-lane-plan
  "Opt-in controller candidate: follow a committed, continuous lateral path
  rather than jumping the pursuit target across the road. Not yet used by the
  live simulation; validate with the physical lane-change/cornering fixtures."
  :- Control [[car physics/Vehicle] [desired-speed :f32] [lane-metres :f32]
               [plan [:* LanePlan]]]
  (let [^:var control (follow car desired-speed lane-metres)
        distance (* (az/field control progress) 4309.0)
        ahead (+ 6.0 (* (az/field control speed) 0.16))]
    (update-lane-plan! plan distance (az/field control lane) (az/field control speed) lane-metres)
    (let [offset (az/index (lane-plan-state plan (+ distance ahead)) 0)
          target (circuit/at-distance (+ distance ahead) offset)
          state (physics/body-state (az/field car chassis))
          delta (b3/b3InvRotateVector (b3/b3Body_GetRotation (az/field car chassis))
                  (b3/b3Vec3 {:x (- (az/field target x) (az/field state x))
                              :y (- (az/field target y) (az/field state y)) :z 0.0}))
          wheelbase (- (az/index (az/index spec/wheel-geometry 0) 0)
                       (az/index (az/index spec/wheel-geometry 2) 0))
          steering (math/atan2 (* 2.0 wheelbase (az/field delta y))
                     (ak/max 1.0 (+ (* (az/field delta x) (az/field delta x))
                                   (* (az/field delta y) (az/field delta y)))))]
      (set! (az/field control steering) (ak/max -0.45 (ak/min 0.45 steering))))
    control))

(az/defn follow-pit
  "Follow the physical pit apron using pedals and steering; cap at 80km/h."
  :- Control [[car physics/Vehicle] [desired-speed :f32] [work-offset :f32]]
  (follow-route car (ak/min desired-speed (/ 80.0 3.6)) work-offset true))

(az/defn yield-to-traffic
  "Reflex braking for an occupied lane. AI still chooses steering and pace;
  this only changes pedals, never body velocity or position. Gap is the
  measured forward arc distance in metres, not a rank difference."
  :- Control [[control Control] [gap :f32] [front-speed :f32]]
  (let [^:var result control
        speed (az/field control speed)
        space (ak/max 0.0 (- gap 6.0 (* speed 0.3)))
        safe-speed (ak/sqrt (+ (* front-speed front-speed) (* 20.0 space)))
        excess (- speed safe-speed)]
    (when (or (> excess 0.0) (<= space 0.01))
      (set! (az/field result throttle) 0.0)
      (set! (az/field result brake)
            (ak/max (az/field result brake)
                    (if (and (< gap 6.0) (< front-speed 0.2))
                      (ak/as :f32 1.0)
                      (clamp-unit (* excess 0.18))))))
    result))

(az/defn apply! :- :void [[car physics/Vehicle] [control Control]]
  (physics/drive! car (az/field control throttle) (az/field control brake)
                  (az/field control steering)))

(az/defn yield-to-offset-obstacle
  "Permit low-speed clearance only when the requested steering points away
  from an offset stationary obstacle. Otherwise retain ordinary traffic braking.
  This does not choose a lane: it lets the AI's existing steering take effect.
  Contacts still constrain motion; only throttle/brake outputs are adjusted."
  :- Control [[control Control] [gap :f32] [front-speed :f32] [side-distance :f32]]
  (if (and (< (az/field control speed) 2.0) (< front-speed 0.2)
           (> gap 0.0) (> (ak/abs side-distance) 2.0)
           (< (* (az/field control steering) side-distance) -0.08))
    (let [^:var result control]
      (set! (az/field result throttle)
            (if (< (az/field control speed) 1.5)
              (ak/min (az/field control throttle) 0.15) 0.0))
      (set! (az/field result brake)
            (clamp-unit (* (- (az/field control speed) 1.5) 0.5)))
      result)
    (yield-to-traffic control gap front-speed)))
