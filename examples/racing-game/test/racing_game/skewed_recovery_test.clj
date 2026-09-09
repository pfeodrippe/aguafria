(ns racing-game.skewed-recovery-test
  "Isolated reproduction of the live R3/R2 obstruction. Never resets the race."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.physics-track :as terrain]
            [racing-game.track :as track]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-recovery :as recovery]
            [racing-game.vehicle-turnaround :as turnaround]
            [clojure.test :refer [deftest is]]))

;; Actual measured poses, metres and quaternion XYZW. R2 then R3; chassis,
;; front-left/right and rear-left/right. Transforms are INITIAL CONDITIONS only.
(az/defconst captured-poses [:array 2 [:array 5 [:array 7 :f32]]]
  [[[-519.4334 113.18194 42.103687 0.15617628 0.98694754 0.013542733 -0.036881413]
    [-520.7595 114.64739 42.458508 -0.16371362 -0.9864303 -0.0058765584 -0.010892344]
    [-521.3711 112.76482 42.42466 0.008226383 0.10983346 0.15080467 -0.9824087]
    [-517.5158 113.59758 42.2008 -0.06732638 -0.47679594 -0.13943347 0.8652694]
    [-518.1227 111.71314 42.171345 -0.15151283 -0.9770049 -0.029219273 0.1471447]]
   [[-524.51984 113.400345 42.819576 0.023166152 0.032577056 0.02766186 0.99881774]
    [-522.8591 114.49372 42.59929 0.16537018 0.97111803 -0.019820549 0.17084973]
    [-522.75 112.519394 42.50961 0.13134627 0.71611756 0.13658401 -0.6717654]
    [-526.2649 114.29889 42.82655 0.024500271 0.08444735 0.029857343 0.9956792]
    [-526.1572 112.32173 42.733444 0.035371397 -0.63074493 0.008667751 0.77513534]]])

(az/defn create-captured-car
  :- physics/Vehicle [[world b3/b3WorldId] [index :usize]]
  (let [p (az/index (az/index captured-poses index) 0)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (az/index p 0) :y (az/index p 1) :z (az/index p 2)}) 0.0)]
    (dotimes [i 5]
      (let [pose (az/index (az/index captured-poses index) i)
            body (if (ak/== i 0) (az/field car chassis)
                   (az/index (az/field car wheels) (- i 1)))]
        (b3/b3Body_SetTransform body
          (b3/b3Pos {:x (az/index pose 0) :y (az/index pose 1) :z (az/index pose 2)})
          (b3/b3Quat {:v {:x (az/index pose 3) :y (az/index pose 4) :z (az/index pose 5)}
                       :s (az/index pose 6)}))))
    car))


(az/defn skewed-probe
  "Captured ten-body initial conditions, no transforms/velocity writes after
  setup. Fixed -3.75m lane intent isolates manoeuvring from model variability.
  Returns forward metres, max lane, min upright, reverse metres, phase mask,
  and final speed. Margin parameter tests the production eligibility gate."
  :- [:array 6 :f32] [[seconds :usize] [road-margin :f32]]
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        other (create-captured-car world 0)
        car (create-captured-car world 1)
        start (az/field (driver/follow car 8.0 -3.75) progress)
        ^:var state (mem/zeroes (az/type recovery/State))
        ^{:var :u8} phases 0
        ^{:var :f32} lane 0.0
        ^{:var :f32} up 1.0
        ^{:var :f32} reverse-travel 0.0]
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (dotimes [_ (* seconds 120)]
      (let [normal (driver/follow car 8.0 -3.75)
            blocked (driver/follow other 0.0 -2.1)
            gap (* 4309.0 (mod (- (az/field blocked progress) (az/field normal progress)) 1.0))
            side (- (az/field blocked lane) (az/field normal lane))
            body (physics/body-state (az/field car chassis))
            other-body (physics/body-state (az/field other chassis))
            route (circuit/at-distance (* 4309.0 (az/field normal progress)) 0.0)
            close? (< (ak/abs side) (turnaround/recovery-side-clearance body other-body
                                     (az/field route heading)))
            traffic (if close? (driver/yield-to-offset-obstacle normal gap (az/field blocked speed) side) normal)
            heading (math/atan2 (* 2.0 (+ (* (az/field body qw) (az/field body qz))
                                           (* (az/field body qx) (az/field body qy))))
                       (- 1.0 (* 2.0 (+ (* (az/field body qy) (az/field body qy))
                                         (* (az/field body qz) (az/field body qz))))))
            enabled (and (> (math/cos (- heading (az/field route heading))) 0.8)
                           (< (ak/abs (az/field normal lane)) road-margin))
            output (recovery/step state normal traffic body -3.75
                     (if close? gap 1000.0) 1000.0 0.0 enabled)
            ^:var bodies (mem/zeroes (az/type [:array protocol/racer-count physics/BodyState]))]
        (set! (az/index bodies 0) body)
        (set! (az/index bodies 1) other-body)
        (set! state (az/field output state))
        (set! phases (ak/| phases (ak/<< (ak/as :u8 1) (ak/intCast (az/field state phase)))))
        (set! lane (ak/max lane (ak/abs (az/field normal lane))))
        (set! up (ak/min up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                                            (* (az/field body qy) (az/field body qy)))))))
        (set! reverse-travel (ak/max reverse-travel (* 4309.0 (- start (az/field normal progress)))))
        (let [control (if (ak/!= (az/field (az/field output state) phase) 0)
                        (turnaround/guard-recovery-control (az/field output control) body
                          bodies 2 0 (az/field output gear) world road-margin)
                        (az/field output control))]
        (dotimes [_ (ak/divTrunc physics/step-rate 120)]
          (physics/drive-in-gear! car (az/field control throttle)
            (az/field control brake) (az/field control steering)
            (az/field output gear))
          (physics/drive! other 0.0 1.0 0.0)
          (physics/step! world)))))
    (let [final (driver/follow car 8.0 -3.75)]
      (az/array-init [:array 6 :f32]
        [(* 4309.0 (- (az/field final progress) start)) lane up reverse-travel
         (ak/as :f32 (ak/floatFromInt phases)) (az/field final speed)]))))

(deftest skewed-wreck-clearance-test
  (let [[forward lane up reverse-distance phases speed]
        (az/value (skewed-probe 40 recovery/corridor-half-width))]
    (is (> forward 30.0) "Escape the captured obstruction and resume progress")
    (is (< lane 7.5) "Stay within the supported low-speed recovery corridor")
    (is (> up 0.9) "Remain upright through the manoeuvre")
    (is (< 2.5 reverse-distance 4.5) "Back away under wheel power")
    (is (= 31.0 (double phases)) "Exercise all five recovery phases")
    (is (> speed 4.0) "Resume ordinary driving after clearing the wreck")))
