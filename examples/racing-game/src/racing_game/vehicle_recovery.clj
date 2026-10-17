(ns racing-game.vehicle-recovery
  "Low-speed manoeuvring toward an already chosen lane. No tactical lane\n  selection, body transforms, velocity writes or collision exemptions."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.keyword :as ak :refer :all]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]
            [racing-game.physics :as physics]
            [racing-game.vehicle-driver :as driver]
            [racing-game.circuit :as circuit]
            [racing-game.track :as track]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]))

(a/defconst corridor-half-width
  :f32 "Low-speed recovery may use one metre of supported shoulder, not arbitrary
  off-course space. Normal racing still targets the driver's ordinary lanes."
  7.5)

(a/defstruct State {:layout :extern}
             [[:phase :u8] [:waiting_ticks :u16] [:start_x :f32] [:start_y :f32]])

(a/defstruct Output {:layout :extern}
             [[:state State] [:control driver/Control] [:gear :i8]])

(a/defn reverse-steering :f32
  "Align to the road while reversing, not to the eventual lateral destination." [[body physics/BodyState]]
  (let [projection (track/project (* (a/field body x) 0.001) (* (a/field body y) 0.001))
        route (circuit/at-distance (* 4309.0 (a/field projection progress)) 0.0)
        heading (math/atan2 (* 2.0 (+ (* (a/field body qw) (a/field body qz))
                                      (* (a/field body qx) (a/field body qy))))
                            (- 1.0 (* 2.0 (+ (* (a/field body qy) (a/field body qy))
                                             (* (a/field body qz) (a/field body qz))))))
        heading-error (- heading (a/field route heading))]
    (max -0.45 (min 0.45 (* 1.5 (math/atan2 (math/sin heading-error) (math/cos heading-error)))))))

(a/defn passing-control driver/Control
  "Hold the physically cleared corridor briefly before returning to AI intent.
  Anchor is the measured pose when clearance was obtained, never a teleport." [[body physics/BodyState] [anchor-x :f32] [anchor-y :f32]]
  (let [anchor (track/project (* anchor-x 0.001) (* anchor-y 0.001))
        projection (track/project (* (a/field body x) 0.001) (* (a/field body y) 0.001))
        lane (max (- corridor-half-width)
                  (min corridor-half-width (* 50.0 (a/field anchor lane))))
        target (circuit/at-distance (+ 6.0 (* 4309.0 (a/field projection progress))) lane)
        rotation (b3/b3Quat {:v {:x (a/field body qx) :y (a/field body qy) :z (a/field body qz)}
                             :s (a/field body qw)})
        delta (b3/b3InvRotateVector rotation
                                    (b3/b3Vec3 {:x (- (a/field target x) (a/field body x))
                                                :y (- (a/field target y) (a/field body y)) :z 0.0}))
        wheelbase (- (a/index (a/index spec/wheel-geometry 0) 0)
                     (a/index (a/index spec/wheel-geometry 2) 0))
        steering (math/atan2 (* 2.0 wheelbase (a/field delta y))
                             (max 1.0 (+ (* (a/field delta x) (a/field delta x))
                                         (* (a/field delta y) (a/field delta y)))))
        speed (sqrt (+ (* (a/field body vx) (a/field body vx))
                       (* (a/field body vy) (a/field body vy))))]
    (driver/Control {:throttle (driver/clamp-unit (* (- 2.0 speed) 0.25))
                     :brake (driver/clamp-unit (* (- speed 2.0) 0.5))
                     :steering (max -0.45 (min 0.45 steering))
                     :progress (a/field projection progress)
                     :lane (* 50.0 (a/field projection lane)) :speed speed})))

(a/defn step Output
  "Run once per 120Hz tick. Phases: 0 ordinary driving, 1 reverse, 2 braking,
  3 seek clearance, 4 pass the obstruction before merging back to AI intent.
  Requires three seconds stationary, an outstanding lane correction and a
  clear rear corridor. Front/rear gaps are measured metres;
  rear-closing is positive approach speed. Caller must exclude pits, retired
  cars and human control. If disabled, brake an active manoeuvre to a stop."
  [[previous State] [normal driver/Control] [traffic driver/Control]
   [body physics/BodyState] [lane-target :f32] [front-gap :f32]
   [rear-gap :f32] [rear-closing :f32] [enabled :bool]]
  (let [^:var state previous
        ^:var control traffic
        ^:var gear (i8 1)
        speed (a/field normal speed)
        up (- 1.0 (* 2.0 (+ (* (a/field body qx) (a/field body qx))
                            (* (a/field body qy) (a/field body qy)))))
        safe (and enabled (> up 0.8))
        rear-clear (and (> rear-gap 14.0)
                        (> (- rear-gap 7.0) (* (max 0.0 rear-closing) 4.0)))
        dx (- (a/field body x) (a/field state start_x))
        dy (- (a/field body y) (a/field state start_y))
        travelled (sqrt (+ (* dx dx) (* dy dy)))]
    (when (and (! safe) (!= (a/field state phase) 0))
      (= (a/field state phase) 2))
    (cond
      (== (a/field state phase) 0)
      (do
        (if (and safe (< speed 0.1) (> front-gap 0.0) (< front-gap 8.0)
                 (> (abs (- lane-target (a/field normal lane))) 0.5)
                 (> (abs (a/field normal steering)) 0.1) rear-clear)
          (= (a/field state waiting_ticks)
             (min 360 (+ (a/field state waiting_ticks) 1)))
          (= (a/field state waiting_ticks) 0))
        (when (>= (a/field state waiting_ticks) 360)
          (= (a/field state phase) 1)
          (= (a/field state start_x) (a/field body x))
          (= (a/field state start_y) (a/field body y))))

      (== (a/field state phase) 1)
      (do
        (= gear -1)
        (= (a/field control steering) (reverse-steering body))
        (= (a/field control throttle) (driver/clamp-unit (* (- 1.2 speed) 0.25)))
        (= (a/field control brake) (driver/clamp-unit (* (- speed 1.2) 0.5)))
        (when (or (>= travelled 4.0) (! rear-clear))
          (= (a/field state phase) 2)
          (= (a/field control throttle) 0.0)
          (= (a/field control brake) 1.0)))

      (== (a/field state phase) 2)
      (do
        (= gear 0)
        (= (a/field control throttle) 0.0)
        (= (a/field control brake) 1.0)
        (= (a/field control steering) 0.0)
        (when (< speed 0.05)
          (= (a/field state phase) (if safe (as 3 :u8) 0))
          (= (a/field state waiting_ticks) 0)
          (= (a/field state start_x) (a/field body x))
          (= (a/field state start_y) (a/field body y))))

      (== (a/field state phase) 3)
      (do
        ;; The same pursuit steering still follows the current model intent.
        ;; Creep cannot push straight into a centred obstacle without turning.
        (when (and safe (> (abs (a/field normal steering)) 0.1))
          (= control normal)
          (= (a/field control throttle) (driver/clamp-unit (* (- 2.0 speed) 0.25)))
          (= (a/field control brake) (driver/clamp-unit (* (- speed 2.0) 0.5))))
        (cond
          (> front-gap 18.0)
          (do
            (= (a/field state phase) 4)
            (= (a/field state start_x) (a/field body x))
            (= (a/field state start_y) (a/field body y))
            (= (a/field state waiting_ticks) 0))

          (> travelled 15.0)
          (do
            (= (a/field state phase) 0)
            (= (a/field state waiting_ticks) 0))))

      (== (a/field state phase) 4)
      (do
        (= control (passing-control body (a/field state start_x) (a/field state start_y)))
        (when (< front-gap 6.0)
          (= (a/field control throttle) 0.0)
          (= (a/field control brake) 1.0))
        (= (a/field state waiting_ticks) (min 1800 (+ (a/field state waiting_ticks) 1)))
        ;; Keep the rear of the car clear before rejoining. A new obstruction
        ;; cannot leave this temporary corridor controller active indefinitely.
        (when (or (> travelled 15.0) (>= (a/field state waiting_ticks) 1800))
          (= (a/field state phase) 0)
          (= (a/field state waiting_ticks) 0)))

      :else (= (a/field state phase) 0))
    (Output {:state state :control control :gear gear})))
