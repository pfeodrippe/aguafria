(ns racing-game.vehicle-recovery-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.physics-track :as terrain]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-recovery :as recovery]
            [racing-game.vehicle-turnaround :as turnaround]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.test :refer [deftest is]]))

(az/defn eligibility-probe
  :- recovery/Output
  [[rear-gap :f32] [rear-closing :f32] [lane-target :f32] [enabled :bool]]
  (let [^:var state (mem/zeroes (az/type recovery/State))
        ^:var body (mem/zeroes (az/type physics/BodyState))
        control (driver/Control {:throttle 1.0 :brake 0.0 :steering 0.4
                                  :progress 0.5 :lane 0.0 :speed 0.0})
        traffic (driver/yield-to-traffic control 6.0 0.0)]
    (set! (az/field body qw) 1.0)
    (dotimes [_ 360]
      (set! state (az/field (recovery/step state control traffic body lane-target
                            6.0 rear-gap rear-closing enabled) state)))
    (recovery/step state control traffic body lane-target
                   6.0 rear-gap rear-closing enabled)))

(deftest reverse-requires-clearance-and-existing-lane-intent-test
  (let [allowed (az/value (eligibility-probe 50.0 0.0 3.75 true))]
    (is (= 1 (get-in allowed [:state :phase])))
    (is (= -1 (:gear allowed)))
    (is (pos? (get-in allowed [:control :throttle])))
    (doseq [[rear closing target enabled] [[10.0 0.0 3.75 true]
                                          [50.0 20.0 3.75 true]
                                          [50.0 0.0 0.0 true]
                                          [50.0 0.0 3.75 false]]]
      (let [result (az/value (eligibility-probe rear closing target enabled))]
        (is (= 0 (get-in result [:state :phase])) (pr-str result))
        (is (= 1 (:gear result)) (pr-str result))))))

(az/defn transition-probe
  :- recovery/Output
  [[phase :u8] [distance :f32] [speed :f32] [rear-gap :f32]
   [rear-closing :f32] [enabled :bool]]
  (let [state (recovery/State {:phase phase :waiting_ticks 0 :start_x 0.0 :start_y 0.0})
        ^:var body (mem/zeroes (az/type physics/BodyState))
        control (driver/Control {:throttle 1.0 :brake 0.0 :steering 0.4
                                  :progress 0.5 :lane 0.0 :speed speed})]
    (set! (az/field body qw) 1.0)
    (set! (az/field body x) distance)
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
    (let [result (az/value (transition-probe phase distance speed rear closing enabled))]
      (is (= expected (get-in result [:state :phase])) (pr-str result))
      (is (zero? (get-in result [:control :throttle])) (pr-str result))
      (is (= 1.0 (get-in result [:control :brake])) (pr-str result)))))

(az/defn circuit-clearance-probe
  "Reproduce the live blocked-car geometry in a separate physical world.
  The test supplies a fixed right-lane intent, not a claimed model decision."
  :- [:array 12 :f32] []
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        start (circuit/at-distance (* 0.6657818 4309.0) -3.66)
        obstruction (circuit/at-distance (* 0.66716516 4309.0) -4.64)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (az/field start x) :y (az/field start y)
                         :z (+ (az/field start z) 0.76)}) (az/field start heading))
        other (physics/create-vehicle world
                (b3/b3Pos {:x (az/field obstruction x) :y (az/field obstruction y)
                           :z (+ (az/field obstruction z) 0.76)}) (az/field obstruction heading))
        ^:var state (mem/zeroes (az/type recovery/State))
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
            gap (* 4309.0 (mod (- (az/field blocked progress) (az/field normal progress)) 1.0))
            side (- (az/field blocked lane) (az/field normal lane))
            body (physics/body-state (az/field car chassis))
            other-body (physics/body-state (az/field other chassis))
            route (circuit/at-distance (* 4309.0 (az/field normal progress)) 0.0)
            close? (< (ak/abs side) (turnaround/recovery-side-clearance body other-body
                                     (az/field route heading)))
            obstacle-gap (if close? gap (ak/as :f32 1000.0))
            traffic (if close?
                      (driver/yield-to-offset-obstacle normal gap (az/field blocked speed) side) normal)
            output (recovery/step state normal traffic body 3.75 obstacle-gap 1000.0 0.0 true)
            ^:var bodies (mem/zeroes (az/type [:array protocol/racer-count physics/BodyState]))]
        (set! (az/index bodies 0) body)
        (set! (az/index bodies 1) other-body)
        (set! state (az/field output state))
        (set! phases (ak/| phases (ak/<< (ak/as :u8 1) (ak/intCast (az/field state phase)))))
        (set! lane (ak/max lane (ak/abs (az/field normal lane))))
        (set! up (ak/min up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                                            (* (az/field body qy) (az/field body qy)))))))
        (set! reverse-travel (ak/max reverse-travel (* 4309.0 (- 0.6657818 (az/field normal progress)))))
        (let [control (if (ak/!= (az/field (az/field output state) phase) 0)
                        (turnaround/guard-recovery-control (az/field output control) body
                          bodies 2 0 (az/field output gear) world recovery/corridor-half-width)
                        (az/field output control))]
        (when (ak/== (az/field state phase) 4)
          (set! pass-separation (ak/min pass-separation
            (turnaround/footprint-separation (az/field body x) (az/field body y)
              (turnaround/heading body) (az/index bodies 1)))))
        (when (and (> (az/field (az/field output control) throttle) 0.0)
                   (ak/== (az/field control throttle) 0.0)
                   (ak/== (az/field control brake) 1.0))
          (set! veto-count (+ veto-count 1.0))
          (when (ak/== (az/field state phase) 3) (set! seek-veto (+ seek-veto 1.0)))
          (when (ak/== (az/field state phase) 4) (set! pass-veto (+ pass-veto 1.0)))
          (set! last-reason (ak/floatFromInt
            (turnaround/motion-clearance-reasons body bodies 2 0 (az/field output gear)
              (az/field control steering) world recovery/corridor-half-width))))
        (dotimes [_ (ak/divTrunc physics/step-rate 120)]
          (physics/drive-in-gear! car (az/field control throttle)
              (az/field control brake) (az/field control steering)
              (az/field output gear))
          (physics/drive! other 0.0 1.0 0.0)
          (physics/step! world)))))
    (az/array-init [:array 12 :f32]
      [(* 4309.0 (- (az/field (driver/follow car 8.0 3.75) progress) 0.6657818))
       lane up reverse-travel (ak/as :f32 (ak/floatFromInt phases))
       veto-count seek-veto pass-veto pass-separation last-reason
       (ak/as :f32 (ak/floatFromInt (az/field state phase)))
       (az/field (driver/follow car 8.0 3.75) speed)])))

(deftest car-physically-backs-up-and-clears-obstruction-test
  (let [[distance lane up reverse-travel phases :as result] (az/value (circuit-clearance-probe))]
    (is (> distance 20.0) (pr-str result))
    (is (< lane 4.5) (pr-str result))
    (is (> up 0.8) (pr-str result))
    (is (< 3.0 reverse-travel 5.5) (pr-str result))
    (is (= 31.0 phases) "All five manoeuvre phases must actually execute")))
