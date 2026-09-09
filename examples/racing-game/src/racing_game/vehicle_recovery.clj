(ns racing-game.vehicle-recovery
  "Low-speed manoeuvring toward an already chosen lane. No tactical lane
  selection, body transforms, velocity writes or collision exemptions."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [racing-game.physics :as physics]
            [racing-game.vehicle-driver :as driver]
            [racing-game.circuit :as circuit]
            [racing-game.track :as track]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]))

(az/defconst corridor-half-width
  "Low-speed recovery may use one metre of supported shoulder, not arbitrary
  off-course space. Normal racing still targets the driver's ordinary lanes."
  :f32 7.5)

(az/defstruct State {:layout :extern}
  [[:phase :u8] [:waiting_ticks :u16] [:start_x :f32] [:start_y :f32]])

(az/defstruct Output {:layout :extern}
  [[:state State] [:control driver/Control] [:gear :i8]])

(az/defn reverse-steering
  "Align to the road while reversing, not to the eventual lateral destination."
  :- :f32 [[body physics/BodyState]]
  (let [projection (track/project (* (az/field body x) 0.001) (* (az/field body y) 0.001))
        route (circuit/at-distance (* 4309.0 (az/field projection progress)) 0.0)
        heading (math/atan2 (* 2.0 (+ (* (az/field body qw) (az/field body qz))
                                       (* (az/field body qx) (az/field body qy))))
                   (- 1.0 (* 2.0 (+ (* (az/field body qy) (az/field body qy))
                                     (* (az/field body qz) (az/field body qz))))))
        heading-error (- heading (az/field route heading))]
    (ak/max -0.45 (ak/min 0.45 (* 1.5 (math/atan2 (math/sin heading-error) (math/cos heading-error)))))))

(az/defn passing-control
  "Hold the physically cleared corridor briefly before returning to AI intent.
  Anchor is the measured pose when clearance was obtained, never a teleport."
  :- driver/Control [[body physics/BodyState] [anchor-x :f32] [anchor-y :f32]]
  (let [anchor (track/project (* anchor-x 0.001) (* anchor-y 0.001))
        projection (track/project (* (az/field body x) 0.001) (* (az/field body y) 0.001))
        lane (ak/max (- corridor-half-width)
                     (ak/min corridor-half-width (* 50.0 (az/field anchor lane))))
        target (circuit/at-distance (+ 6.0 (* 4309.0 (az/field projection progress))) lane)
        rotation (b3/b3Quat {:v {:x (az/field body qx) :y (az/field body qy) :z (az/field body qz)}
                             :s (az/field body qw)})
        delta (b3/b3InvRotateVector rotation
                (b3/b3Vec3 {:x (- (az/field target x) (az/field body x))
                            :y (- (az/field target y) (az/field body y)) :z 0.0}))
        wheelbase (- (az/index (az/index spec/wheel-geometry 0) 0)
                      (az/index (az/index spec/wheel-geometry 2) 0))
        steering (math/atan2 (* 2.0 wheelbase (az/field delta y))
                   (ak/max 1.0 (+ (* (az/field delta x) (az/field delta x))
                                  (* (az/field delta y) (az/field delta y)))))
        speed (ak/sqrt (+ (* (az/field body vx) (az/field body vx))
                          (* (az/field body vy) (az/field body vy))))]
    (driver/Control {:throttle (driver/clamp-unit (* (- 2.0 speed) 0.25))
                     :brake (driver/clamp-unit (* (- speed 2.0) 0.5))
                     :steering (ak/max -0.45 (ak/min 0.45 steering))
                     :progress (az/field projection progress)
                     :lane (* 50.0 (az/field projection lane)) :speed speed})))

(az/defn step
  "Run once per 120Hz tick. Phases: 0 ordinary driving, 1 reverse, 2 braking,
  3 seek clearance, 4 pass the obstruction before merging back to AI intent.
  Requires three seconds stationary, an outstanding lane correction and a
  clear rear corridor. Front/rear gaps are measured metres;
  rear-closing is positive approach speed. Caller must exclude pits, retired
  cars and human control. If disabled, brake an active manoeuvre to a stop."
  :- Output
  [[previous State] [normal driver/Control] [traffic driver/Control]
   [body physics/BodyState] [lane-target :f32] [front-gap :f32]
   [rear-gap :f32] [rear-closing :f32] [enabled :bool]]
  (let [^:var state previous
        ^:var control traffic
        ^{:var :i8} gear 1
        speed (az/field normal speed)
        up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                            (* (az/field body qy) (az/field body qy)))))
        safe (and enabled (> up 0.8))
        rear-clear (and (> rear-gap 14.0)
                         (> (- rear-gap 7.0) (* (ak/max 0.0 rear-closing) 4.0)))
        dx (- (az/field body x) (az/field state start_x))
        dy (- (az/field body y) (az/field state start_y))
        travelled (ak/sqrt (+ (* dx dx) (* dy dy)))]
    (when (and (ak/! safe) (ak/!= (az/field state phase) 0))
      (set! (az/field state phase) 2))
    (cond
      (ak/== (az/field state phase) 0)
      (do
        (if (and safe (< speed 0.1) (> front-gap 0.0) (< front-gap 8.0)
                 (> (ak/abs (- lane-target (az/field normal lane))) 0.5)
                 (> (ak/abs (az/field normal steering)) 0.1) rear-clear)
          (set! (az/field state waiting_ticks)
                (ak/min 360 (+ (az/field state waiting_ticks) 1)))
          (set! (az/field state waiting_ticks) 0))
        (when (>= (az/field state waiting_ticks) 360)
          (set! (az/field state phase) 1)
          (set! (az/field state start_x) (az/field body x))
          (set! (az/field state start_y) (az/field body y))))

      (ak/== (az/field state phase) 1)
      (do
        (set! gear -1)
        (set! (az/field control steering) (reverse-steering body))
        (set! (az/field control throttle) (driver/clamp-unit (* (- 1.2 speed) 0.25)))
        (set! (az/field control brake) (driver/clamp-unit (* (- speed 1.2) 0.5)))
        (when (or (>= travelled 4.0) (ak/! rear-clear))
          (set! (az/field state phase) 2)
          (set! (az/field control throttle) 0.0)
          (set! (az/field control brake) 1.0)))

      (ak/== (az/field state phase) 2)
      (do
        (set! gear 0)
        (set! (az/field control throttle) 0.0)
        (set! (az/field control brake) 1.0)
        (set! (az/field control steering) 0.0)
        (when (< speed 0.05)
          (set! (az/field state phase) (if safe (ak/as :u8 3) 0))
          (set! (az/field state waiting_ticks) 0)
          (set! (az/field state start_x) (az/field body x))
          (set! (az/field state start_y) (az/field body y))))

      (ak/== (az/field state phase) 3)
      (do
        ;; The same pursuit steering still follows the current model intent.
        ;; Creep cannot push straight into a centred obstacle without turning.
        (when (and safe (> (ak/abs (az/field normal steering)) 0.1))
          (set! control normal)
          (set! (az/field control throttle) (driver/clamp-unit (* (- 2.0 speed) 0.25)))
          (set! (az/field control brake) (driver/clamp-unit (* (- speed 2.0) 0.5))))
        (cond
          (> front-gap 18.0)
          (do
            (set! (az/field state phase) 4)
            (set! (az/field state start_x) (az/field body x))
            (set! (az/field state start_y) (az/field body y))
            (set! (az/field state waiting_ticks) 0))

          (> travelled 15.0)
          (do
            (set! (az/field state phase) 0)
            (set! (az/field state waiting_ticks) 0))))

      (ak/== (az/field state phase) 4)
      (do
        (set! control (passing-control body (az/field state start_x) (az/field state start_y)))
        (when (< front-gap 6.0)
          (set! (az/field control throttle) 0.0)
          (set! (az/field control brake) 1.0))
        (set! (az/field state waiting_ticks) (ak/min 1800 (+ (az/field state waiting_ticks) 1)))
        ;; Keep the rear of the car clear before rejoining. A new obstruction
        ;; cannot leave this temporary corridor controller active indefinitely.
        (when (or (> travelled 15.0) (>= (az/field state waiting_ticks) 1800))
          (set! (az/field state phase) 0)
          (set! (az/field state waiting_ticks) 0)))

      :else (set! (az/field state phase) 0))
    (Output {:state state :control control :gear gear})))
