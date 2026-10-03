(ns racing-game.vehicle-recovery-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.physics-track :as terrain]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-recovery :as recovery]
            [racing-game.vehicle-turnaround :as turnaround]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.test :refer [deftest is]]))

(a/defn eligibility-probe recovery/Output
  [[rear-gap :f32] [rear-closing :f32] [lane-target :f32] [enabled :bool]]
  (let [^:var state (mem/zeroes (a/type recovery/State))
        ^:var body (mem/zeroes (a/type physics/BodyState))
        control (driver/Control {:throttle 1.0 :brake 0.0 :steering 0.4
                                  :progress 0.5 :lane 0.0 :speed 0.0})
        traffic (driver/yield-to-traffic control 6.0 0.0)]
    (ak/= (a/field body qw) 1.0)
    (dotimes [_ 360]
      (ak/= state (a/field (recovery/step state control traffic body lane-target
                            6.0 rear-gap rear-closing enabled) state)))
    (recovery/step state control traffic body lane-target
                   6.0 rear-gap rear-closing enabled)))

(deftest reverse-requires-clearance-and-existing-lane-intent-test
  (let [allowed (a/value (eligibility-probe 50.0 0.0 3.75 true))]
    (is (= 1 (get-in allowed [:state :phase])))
    (is (= -1 (:gear allowed)))
    (is (pos? (get-in allowed [:control :throttle])))
    (doseq [[rear closing target enabled] [[10.0 0.0 3.75 true]
                                          [50.0 20.0 3.75 true]
                                          [50.0 0.0 0.0 true]
                                          [50.0 0.0 3.75 false]]]
      (let [result (a/value (eligibility-probe rear closing target enabled))]
        (is (= 0 (get-in result [:state :phase])) (pr-str result))
        (is (= 1 (:gear result)) (pr-str result))))))

(a/defn transition-probe recovery/Output
  [[phase :u8] [distance :f32] [speed :f32] [rear-gap :f32]
   [rear-closing :f32] [enabled :bool]]
  (let [state (recovery/State {:phase phase :waiting_ticks 0 :start_x 0.0 :start_y 0.0})
        ^:var body (mem/zeroes (a/type physics/BodyState))
        control (driver/Control {:throttle 1.0 :brake 0.0 :steering 0.4
                                  :progress 0.5 :lane 0.0 :speed speed})]
    (ak/= (a/field body qw) 1.0)
    (ak/= (a/field body x) distance)
    (recovery/step state control control body 3.75 6.0 rear-gap rear-closing enabled)))

(deftest reverse-brakes-before-changing-direction-and-when-rear-closes-test
  (doseq [[phase distance speed rear closing enabled expected]
          [[1 4.1 1.2 50.0 0.0 true 2]
           [1 1.0 1.0 10.0 0.0 true 2]
           [1 1.0 1.0 50.0 20.0 true 2]
           [1 1.0 1.0 50.0 0.0 false 2]
           [2 4.1 1.0 50.0 0.0 true 2]
           [2 4.1 0.0 50.0 0.0 true 3]
           [2 4.1 0.0 50.0 0.0 false 0]
           [4 1.0 1.0 50.0 0.0 false 2]
           [4 1.0 0.0 50.0 0.0 false 0]]]
    (let [result (a/value (transition-probe phase distance speed rear closing enabled))]
      (is (= expected (get-in result [:state :phase])) (pr-str result))
      (is (zero? (get-in result [:control :throttle])) (pr-str result))
      (is (= 1.0 (get-in result [:control :brake])) (pr-str result)))))

(a/defn circuit-clearance-probe [:array 12 :f32]
  "Reproduce the live blocked-car geometry in a separate physical world.
  The test supplies a fixed right-lane intent, not a claimed model decision." []
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        start (circuit/at-distance (* 0.6657818 4309.0) -3.66)
        obstruction (circuit/at-distance (* 0.66716516 4309.0) -4.64)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (a/field start x) :y (a/field start y)
                         :z (+ (a/field start z) 0.76)}) (a/field start heading))
        other (physics/create-vehicle world
                (b3/b3Pos {:x (a/field obstruction x) :y (a/field obstruction y)
                           :z (+ (a/field obstruction z) 0.76)}) (a/field obstruction heading))
        ^:var state (mem/zeroes (a/type recovery/State))
        ^{:var :u8} phases 0
        ^{:var :f32} lane 0.0
        ^{:var :f32} up 1.0
        ^{:var :f32} reverse-travel 0.0
        ^{:var :f32} veto-count 0.0
        ^{:var :f32} seek-veto 0.0
        ^{:var :f32} pass-veto 0.0
        ^{:var :f32} pass-separation 1000.0
        ^{:var :f32} last-reason 0.0]
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (dotimes [_ physics/step-rate]
      (physics/drive! car 0.0 1.0 0.0)
      (physics/drive! other 0.0 1.0 0.0)
      (physics/step! world))
    (dotimes [_ (* 25 120)]
      (let [normal (driver/follow car 8.0 3.75)
            blocked (driver/follow other 0.0 -4.64)
            gap (* 4309.0 (mod (- (a/field blocked progress) (a/field normal progress)) 1.0))
            side (- (a/field blocked lane) (a/field normal lane))
            body (physics/body-state (a/field car chassis))
            other-body (physics/body-state (a/field other chassis))
            route (circuit/at-distance (* 4309.0 (a/field normal progress)) 0.0)
            close? (< (ak/abs side) (turnaround/recovery-side-clearance body other-body
                                     (a/field route heading)))
            obstacle-gap (if close? gap (ak/as 1000.0 :f32))
            traffic (if close?
                      (driver/yield-to-offset-obstacle normal gap (a/field blocked speed) side) normal)
            output (recovery/step state normal traffic body 3.75 obstacle-gap 1000.0 0.0 true)
            ^:var bodies (mem/zeroes (a/type [:array protocol/racer-count physics/BodyState]))]
        (ak/= (a/index bodies 0) body)
        (ak/= (a/index bodies 1) other-body)
        (ak/= state (a/field output state))
        (ak/= phases (ak/| phases (ak/<< (ak/as 1 :u8) (ak/intCast (a/field state phase)))))
        (ak/= lane (ak/max lane (ak/abs (a/field normal lane))))
        (ak/= up (ak/min up (- 1.0 (* 2.0 (+ (* (a/field body qx) (a/field body qx))
                                            (* (a/field body qy) (a/field body qy)))))))
        (ak/= reverse-travel (ak/max reverse-travel (* 4309.0 (- 0.6657818 (a/field normal progress)))))
        (let [control (if (ak/!= (a/field (a/field output state) phase) 0)
                        (turnaround/guard-recovery-control (a/field output control) body
                          bodies 2 0 (a/field output gear) world recovery/corridor-half-width)
                        (a/field output control))]
        (when (ak/== (a/field state phase) 4)
          (ak/= pass-separation (ak/min pass-separation
            (turnaround/footprint-separation (a/field body x) (a/field body y)
              (turnaround/heading body) (a/index bodies 1)))))
        (when (and (> (a/field (a/field output control) throttle) 0.0)
                   (ak/== (a/field control throttle) 0.0)
                   (ak/== (a/field control brake) 1.0))
          (ak/= veto-count (+ veto-count 1.0))
          (when (ak/== (a/field state phase) 3) (ak/= seek-veto (+ seek-veto 1.0)))
          (when (ak/== (a/field state phase) 4) (ak/= pass-veto (+ pass-veto 1.0)))
          (ak/= last-reason (ak/floatFromInt
            (turnaround/motion-clearance-reasons body bodies 2 0 (a/field output gear)
              (a/field control steering) world recovery/corridor-half-width))))
        (dotimes [_ (ak/divTrunc physics/step-rate 120)]
          (physics/drive-in-gear! car (a/field control throttle)
              (a/field control brake) (a/field control steering)
              (a/field output gear))
          (physics/drive! other 0.0 1.0 0.0)
          (physics/step! world)))))
    (a/init [(* 4309.0 (- (a/field (driver/follow car 8.0 3.75) progress) 0.6657818))
       lane up reverse-travel (ak/as (ak/floatFromInt phases) :f32)
       veto-count seek-veto pass-veto pass-separation last-reason
       (ak/as (ak/floatFromInt (a/field state phase)) :f32)
       (a/field (driver/follow car 8.0 3.75) speed)] [:array 12 :f32])))

(deftest car-physically-backs-up-and-clears-obstruction-test
  (let [[distance lane up reverse-travel phases :as result] (a/value (circuit-clearance-probe))]
    (is (> distance 20.0) (pr-str result))
    (is (< lane 4.5) (pr-str result))
    (is (> up 0.8) (pr-str result))
    (is (< 3.0 reverse-travel 5.5) (pr-str result))
    (is (= 31.0 phases) "All five manoeuvre phases must actually execute")))
