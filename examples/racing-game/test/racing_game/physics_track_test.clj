(ns racing-game.physics-track-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.physics-test :as tire-probe]
            [racing-game.physics-track :as terrain]
            [racing-game.vehicle-driver :as driver]
            [racing-game.track :as track]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.test :refer [deftest is]]))

(az/defstruct SurfaceResult {:layout :extern}
  [[:min_clearance :f32] [:max_clearance :f32] [:max_speed :f32]])

(az/defn settled-surface-probe
  "Twelve actual suspended cars distributed over the authored slopes and seam."
  :- SurfaceResult []
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        ^:var cars (mem/zeroes (az/type [:array 12 physics/Vehicle]))]
    ;; Mesh data is borrowed by shapes: release it only after destroying bodies.
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (dotimes [i 12]
      (let [p (circuit/at-distance (* (ak/as :f32 (ak/floatFromInt i)) (/ 4309.0 12.0)) 0.0)]
        (set! (az/index cars i)
              (physics/create-vehicle world
                (b3/b3Pos {:x (az/field p x) :y (az/field p y) :z (+ (az/field p z) 0.76)})
                (az/field p heading)))))
    (dotimes [_ (* 3 physics/step-rate)]
      (dotimes [i 12] (physics/drive! (az/index cars i) 0.0 1.0 0.0))
      (physics/step! world))
    (let [^{:var :f32} low 100.0
          ^{:var :f32} high -100.0
          ^{:var :f32} speed 0.0]
      (dotimes [i 12]
        (let [p (circuit/at-distance (* (ak/as :f32 (ak/floatFromInt i)) (/ 4309.0 12.0)) 0.0)
              state (physics/body-state (az/field (az/index cars i) chassis))
              clearance (- (az/field state z) (az/field p z))]
          (set! low (ak/min low clearance))
          (set! high (ak/max high clearance))
          (set! speed (ak/max speed
            (ak/sqrt (+ (* (az/field state vx) (az/field state vx))
                        (* (az/field state vy) (az/field state vy))
                        (* (az/field state vz) (az/field state vz))))))))
      (SurfaceResult {:min_clearance low :max_clearance high :max_speed speed}))))

(deftest suspended-cars-on-authored-terrain-test
  (let [result (az/value (settled-surface-probe))]
    (is (< 0.40 (:min_clearance result) 0.8) (pr-str result))
    (is (< 0.40 (:max_clearance result) 0.8) (pr-str result))
    (is (< (:max_speed result) 0.1) (pr-str result))))

(az/defstruct DrivenResult {:layout :extern}
  [[:distance :f32] [:maximum_lane_error :f32] [:minimum_up :f32]
   [:maximum_speed :f32] [:final_speed :f32] [:milliseconds :f32]
   [:worst_distance :f32] [:worst_time :f32]
   [:first_lap_seconds :f32] [:flying_lap_seconds :f32]
   [:first_lap_metres :f32] [:maximum_projection_step :f32]])

(az/defn curvature-probe :- [:array 2 :f32] []
  (let [^{:var :f32} curvature 0.0 ^{:var :f32} location 0.0]
    (dotimes [i 4309]
      (let [d (ak/as :f32 (ak/floatFromInt i))
            a (circuit/at-distance (- d 0.5) 0.0) b (circuit/at-distance (+ d 0.5) 0.0)
            delta (- (az/field b heading) (az/field a heading))
            k (ak/abs (math/atan2 (math/sin delta) (math/cos delta)))]
        (when (> k curvature) (set! curvature k) (set! location d))))
    (az/array-init [:array 2 :f32] [location curvature])))

(az/defvar lap-trace [:array 360 [:array 11 :f32]] ak/undefined)

(az/defn trace-at :- [:array 11 :f32] [[index :usize]]
  (az/index lap-trace index))

(az/defn lane-plan-continuity-probe :- [:array 10 :f32] []
  (let [^:var plan (mem/zeroes (az/type driver/LanePlan))]
    (driver/update-lane-plan! (ak/& plan) 0.0 0.0 70.0 3.75)
    (let [middle (* (az/field plan length) 0.4)
          before (driver/lane-plan-state (ak/& plan) middle)]
      (driver/update-lane-plan! (ak/& plan) middle (az/index before 0) 70.0 -3.75)
      (let [after (driver/lane-plan-state (ak/& plan) middle)
            finish (+ middle (az/field plan length))
            end (driver/lane-plan-state (ak/& plan) finish)]
        (driver/update-lane-plan! (ak/& plan) (+ finish 1.0) -3.75 70.0 -3.75)
        (az/array-init [:array 10 :f32]
          [(az/index before 0) (az/index after 0)
           (az/index before 1) (az/index after 1)
           (az/index before 2) (az/index after 2)
           (az/index end 0) (az/index end 1) (az/index end 2)
           (az/index (driver/lane-plan-state (ak/& plan) (+ middle 4309.0)) 0)])))))

(deftest lane-plan-continuity-test
  (let [[p0 p1 v0 v1 a0 a1 end slope curvature next-lap :as result]
        (az/value (lane-plan-continuity-probe))]
    (is (< (abs (- p0 p1)) 0.0001) (pr-str result))
    (is (< (abs (- v0 v1)) 0.0001) (pr-str result))
    (is (< (abs (- a0 a1)) 0.0001) (pr-str result))
    (is (< (abs (+ end 3.75)) 0.0001) (pr-str result))
    (is (< (abs slope) 0.0001) (pr-str result))
    (is (< (abs curvature) 0.0001) (pr-str result))
    (is (< (abs (+ next-lap 3.75)) 0.0001) (pr-str result))))

(az/defn rollover-pedal-probe :- driver/Control [[roll :f32] [gas :f32]]
  (let [^:var body (mem/zeroes (az/type physics/BodyState))
        control (driver/Control {:throttle gas :brake 0.25 :steering 0.12
                                  :progress 0.5 :lane -1.5 :speed 8.0})]
    (set! (az/field body qx) (math/sin (* roll 0.5)))
    (set! (az/field body qw) (math/cos (* roll 0.5)))
    (driver/stop-if-overturned control body)))

(deftest overturning-cuts-pedals-without-changing-measured-state-test
  (doseq [roll [0.0 0.6 -0.6]]
    (let [result (az/value (rollover-pedal-probe roll 0.8))]
      (is (< (abs (- (:throttle result) 0.8)) 0.0001))
      (is (= 0.25 (:brake result)))
      (is (< (abs (- (:steering result) 0.12)) 0.0001))))
  (doseq [roll [1.5707963 3.1415927 -3.1415927]
          gas [0.0 1.0]]
    (let [result (az/value (rollover-pedal-probe roll gas))]
      (is (= [0.0 1.0 0.0] ((juxt :throttle :brake :steering) result)))
      (is (= [0.5 -1.5 8.0] ((juxt :progress :lane :speed) result))))))

(az/defn driven-route-controller-probe
  "Follow the real circuit using only wheel motors, brakes and steering.
  No SetTransform/SetVelocity, progress stepping or lane correction is allowed."
  :- DrivenResult [[seconds :u32] [cruise-speed :f32]
                   [lane-period :u32] [lane-amplitude :f32] [analytic? :bool] [planned? :bool]]
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        p (circuit/at-distance 0.0 0.0)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (az/field p x) :y (az/field p y) :z (+ (az/field p z) 0.76)})
              (az/field p heading))
        ^:var lane-plan (mem/zeroes (az/type driver/LanePlan))]
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (when analytic? (tire-probe/use-ray-tires-probe! car))
    (dotimes [_ physics/step-rate]
      (physics/drive! car 0.0 1.0 0.0)
      (when analytic? (tire-probe/apply-ray-tires-probe! car))
      (physics/step! world))
    (let [^{:var :f32} distance 0.0
          ^{:var :f32} previous 0.0
          ^{:var :f32} lane 0.0
          ^{:var :f32} up 1.0
          ^{:var :f32} speed 0.0
          ^{:var :f32} worst-distance 0.0
          ^{:var :f32} worst-time 0.0
          ^{:var :f32} first-lap 0.0
          ^{:var :f32} flying-lap 0.0
          ^{:var :f32} physical-distance 0.0
          ^{:var :f32} first-lap-metres 0.0
          ^{:var :f32} maximum-projection-step 0.0
          ^:var previous-body (physics/body-state (az/field car chassis))
          started (b3/b3GetTicks)]
      (dotimes [tick (* seconds 120)]
        (let [requested-lane
              (if (or (ak/== lane-period 0) (< tick 2400))
                (ak/as :f32 0.0)
                (if (ak/== (mod (ak/divTrunc (- tick 2400) (* lane-period 120)) 2) 0)
                  lane-amplitude (- lane-amplitude)))
              control (if planned?
                        (driver/follow-lane-plan car cruise-speed requested-lane (ak/& lane-plan))
                        (driver/follow car cruise-speed requested-lane))
              progress (az/field control progress)
              delta (- progress previous)
              state (physics/body-state (az/field car chassis))]
          (let [dx (- (az/field state x) (az/field previous-body x))
                dy (- (az/field state y) (az/field previous-body y))
                dz (- (az/field state z) (az/field previous-body z))]
            (set! physical-distance (+ physical-distance (ak/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
            (set! previous-body state)
            (set! maximum-projection-step
                  (ak/max maximum-projection-step
                    (* 4309.0 (ak/abs (cond (> delta 0.5) (- delta 1.0)
                                            (< delta -0.5) (+ delta 1.0)
                                            :else delta))))))
          (when (and (< tick 43200) (ak/== (mod tick 120) 0))
            (set! (az/index lap-trace (ak/divTrunc tick 120))
                  (az/array-init [:array 11 :f32] [(/ (ak/as :f32 (ak/floatFromInt tick)) 120.0)
                    progress (az/field control lane) (az/field control speed)
                    (az/field control throttle) (az/field control brake) (az/field control steering)
                    (- 1.0 (* 2.0 (+ (* (az/field state qx) (az/field state qx))
                                      (* (az/field state qy) (az/field state qy)))))
                    (b3/b3WheelJoint_GetSteeringAngle (az/index (az/field car joints) 0))
                    (b3/b3WheelJoint_GetSteeringAngle (az/index (az/field car joints) 1))
                    (az/field state wz)])))
          (set! distance (+ distance (* 4309.0 (cond (> delta 0.5) (- delta 1.0)
                                                    (< delta -0.5) (+ delta 1.0)
                                                    :else delta))))
          (set! previous progress)
          ;; Crossing times come from body-derived distance at fixed 120Hz,
          ;; not a distance/average-speed estimate or a wall-clock speedup.
          (when (and (>= distance 4309.0) (ak/== first-lap 0.0))
            (set! first-lap (/ (ak/as :f32 (ak/floatFromInt tick)) 120.0))
            (set! first-lap-metres physical-distance))
          (when (and (>= distance 8618.0) (ak/== flying-lap 0.0))
            (set! flying-lap (- (/ (ak/as :f32 (ak/floatFromInt tick)) 120.0) first-lap)))
          (when (> (ak/abs (az/field control lane)) lane)
            (set! lane (ak/abs (az/field control lane)))
            (set! worst-distance (* progress 4309.0))
            (set! worst-time (/ (ak/as :f32 (ak/floatFromInt tick)) 120.0)))
          (set! speed (ak/max speed (az/field control speed)))
          (set! up (ak/min up (- 1.0 (* 2.0 (+ (* (az/field state qx) (az/field state qx))
                                             (* (az/field state qy) (az/field state qy)))))))
          (dotimes [_ (ak/divTrunc physics/step-rate 120)]
            (driver/apply! car control)
            (when analytic? (tire-probe/apply-ray-tires-probe! car))
            (physics/step! world))))
      (DrivenResult {:distance distance :maximum_lane_error lane :minimum_up up
                      :maximum_speed speed :final_speed (az/field (driver/follow car cruise-speed 0.0) speed)
                      :milliseconds (b3/b3GetMilliseconds started)
                      :worst_distance worst-distance :worst_time worst-time
                      :first_lap_seconds first-lap :flying_lap_seconds flying-lap
                      :first_lap_metres first-lap-metres
                      :maximum_projection_step maximum-projection-step}))))

(az/defn driven-route-contact-probe
  "Compare contacts without changing the production controller."
  :- DrivenResult [[seconds :u32] [cruise-speed :f32]
                   [lane-period :u32] [lane-amplitude :f32] [analytic? :bool]]
  (driven-route-controller-probe seconds cruise-speed lane-period lane-amplitude analytic? false))

(az/defn driven-route-probe
  "Production world-managed contacts and continuous lane planner, as in the game."
  :- DrivenResult [[seconds :u32] [cruise-speed :f32]
                   [lane-period :u32] [lane-amplitude :f32]]
  (driven-route-controller-probe seconds cruise-speed lane-period lane-amplitude false true))

(az/defn driven-circuit-probe
  "Clean centreline control: same physical fixture, no lane changes."
  :- DrivenResult [[seconds :u32] [cruise-speed :f32]]
  (driven-route-probe seconds cruise-speed 0 0.0))

(defn assert-lane-change-result [result]
  (is (> (:distance result) 4309.0) (pr-str result))
  (is (< (:maximum_lane_error result) 5.0) (pr-str result))
  (is (> (:minimum_up result) 0.8) (pr-str result))
  (is (> (:maximum_speed result) 65.0) (pr-str result))
  (is (< (:maximum_projection_step result) 2.0) (pr-str result))
  (is (< 4200.0 (:first_lap_metres result) 4450.0) (pr-str result)))

(deftest motor-driven-lane-changes-test
  ;; Deliberately abrupt model intents, not a pre-smoothed test trajectory.
  ;; Start from rest, accelerate for 20s, then change sides every eight seconds.
  ;; Both polarities exercise inside/outside requests on different corners.
  (doseq [amplitude [3.75 -3.75]]
    (assert-lane-change-result (az/value (driven-route-probe 140 76.0 8 amplitude)))))

(defn assert-cornering-result [cruise result]
    (is (> (:distance result) 4309.0) (pr-str result))
    ;; Reject timing credited by a projection jumping to another track branch.
    (is (< 4200.0 (:first_lap_metres result) 4400.0) (pr-str result))
    (is (< (:maximum_projection_step result) 2.0) (pr-str result))
    (is (< (:maximum_lane_error result) 4.5) (pr-str result))
    (is (> (:minimum_up result) 0.8) (pr-str result))
    (is (> (:maximum_speed result) (if (> cruise 40.0) 70.0 30.0)) (pr-str result))
    ;; Approximately 1m30 or faster flying laps; do not reject a clean lap
    ;; merely for being faster than 80s. A credited lap cannot be shorter than
    ;; the accepted physical distance floor / measured peak speed. The lane,
    ;; upright, physical-distance and projection checks still reject shortcuts.
    (when (> cruise 40.0)
      (is (< 0.0 (:first_lap_seconds result) 97.0) (pr-str result))
      (let [peak (:maximum_speed result)
            minimum-seconds (if (pos? peak) (/ 4200.0 peak) Double/POSITIVE_INFINITY)]
        (is (< minimum-seconds (:flying_lap_seconds result) 92.0) (pr-str result))))
    (is (> (:final_speed result) 5.0) (pr-str result)))

(deftest motor-driven-cornering-test
  ;; Include the model's ordinary 76m/s pace, not only an idealized faster
  ;; request. The cornering/braking controller must still deliver clean laps.
  (doseq [cruise [40.0 76.0 80.0 90.0]]
    (assert-cornering-result cruise
      (az/value (driven-circuit-probe (if (= cruise 40.0) 180 230) cruise)))))

(deftest planned-analytic-lane-changes-test
  ;; Exactly the same abrupt intents and acceptance bounds as production.
  (doseq [amplitude [3.75 -3.75]]
    (assert-lane-change-result
      (az/value (driven-route-controller-probe 140 76.0 8 amplitude true true)))))

(deftest planned-analytic-cornering-test
  (doseq [cruise [40.0 76.0 80.0 90.0]]
    (assert-cornering-result cruise
      (az/value (driven-route-controller-probe
                  (if (= cruise 40.0) 180 230) cruise 0 0.0 true true)))))

(deftest traffic-reflex-only-changes-pedals-test
  (let [intent {:throttle 1.0 :brake 0.0 :steering 0.12
                :progress 0.5 :lane 1.0 :speed 60.0}
        control (driver/Control intent)
        clear (az/value (driver/yield-to-traffic control 500.0 20.0))
        blocked (az/value (driver/yield-to-traffic control 40.0 20.0))
        stopped (az/value (driver/yield-to-traffic
                           (driver/Control (assoc intent :speed 0.0)) 5.0 0.0))]
    (is (= 1.0 (:throttle clear)))
    (is (= 0.0 (:brake clear)))
    (is (= 0.0 (:throttle blocked)))
    (is (= 1.0 (:brake blocked)))
    (is (= (select-keys clear [:steering :progress :lane :speed])
           (select-keys blocked [:steering :progress :lane :speed])))
    (is (= 0.0 (:throttle stopped)))
    (is (= 1.0 (:brake stopped)))))

(deftest offset-obstacle-clearance-respects-requested-steering-test
  (let [intent {:throttle 1.0 :brake 0.0 :steering 0.4
                :progress 0.5 :lane 0.0 :speed 0.0}
        observe #(az/value (driver/yield-to-offset-obstacle
                            (driver/Control intent) 5.9 0.0 %))
        away (observe -2.45)
        toward (observe 2.45)
        centred (observe 0.0)
        beside (az/value (driver/yield-to-offset-obstacle
                           (driver/Control intent) 3.0 0.0 -2.45))
        slowing (az/value (driver/yield-to-offset-obstacle
                            (driver/Control (assoc intent :speed 1.8))
                            3.0 0.0 -2.45))]
    (is (< 0.0 (:throttle away) 0.16))
    (is (= 0.0 (:brake away)))
    (is (pos? (:throttle beside)) "Do not deadlock halfway through clearance")
    (is (= 0.0 (:throttle slowing)))
    (is (pos? (:brake slowing)) "Brake above the creep target, not a velocity clamp")
    (is (= (select-keys (az/value (driver/Control intent)) [:steering :progress :lane :speed])
           (select-keys away [:steering :progress :lane :speed])))
    (doseq [stopped [toward centred]]
      (is (= 0.0 (:throttle stopped)))
      (is (= 1.0 (:brake stopped))))))

(az/defstruct PitResult {:layout :extern}
  [[:progress :f32] [:max_error :f32] [:minimum_up :f32]
   [:stopped_seconds :f32] [:stop_error :f32] [:final_speed :f32]
   [:service_max_speed :f32] [:service_drift :f32]])

(az/defn driven-pit-contact-probe
  "Enter the pit, brake to rest, hold three seconds, then merge under power.
  Measures the actual body; no parking pose or velocity is assigned."
  :- PitResult [[analytic? :bool]]
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        start (circuit/at-distance (* 0.92 4309.0) 0.0)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (az/field start x) :y (az/field start y)
                         :z (+ (az/field start z) 0.76)})
              (az/field start heading))
        ^{:var :f32} stopped 0.0
        ^{:var :f32} stop-error 1000.0
        ^{:var :f32} error-max 0.0
        ^{:var :f32} up 1.0
        ^{:var :f32} progress 0.92
        ^{:var :f32} speed 0.0
        ^{:var :f32} service-speed 0.0
        ^{:var :f32} service-drift 0.0
        ^{:var :f32} stop-x 0.0
        ^{:var :f32} stop-y 0.0]
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (when analytic? (tire-probe/use-ray-tires-probe! car))
    (dotimes [_ physics/step-rate]
      (physics/drive! car 0.0 1.0 0.0)
      (when analytic? (tire-probe/apply-ray-tires-probe! car))
      (physics/step! world))
    (dotimes [_ (* 75 120)]
      (let [observation (driver/follow-pit car 0.0 6.0)
            q (az/field observation progress)
            unwrapped (if (< q 0.5) (+ q 1.0) q)
            remaining (* (- 0.99 unwrapped) 4309.0)
            released (>= stopped 3.0)
            requested (if released (ak/as :f32 22.0)
                         (ak/sqrt (* 6.0 (ak/max 0.0 (- remaining 0.5)))))
            ^:var control (driver/follow-pit car requested 6.0)
            state (physics/body-state (az/field car chassis))]
        (set! progress unwrapped)
        (set! speed (az/field control speed))
        (when (and (ak/! released) (< (ak/abs remaining) 2.0) (< speed 0.02))
          (when (ak/== stopped 0.0)
            (set! stop-x (az/field state x))
            (set! stop-y (az/field state y)))
          (set! stopped (+ stopped (/ 1.0 120.0)))
          (set! stop-error (ak/abs remaining)))
        (when (and (ak/! released) (> stopped 0.0))
          (let [dx (- (az/field state x) stop-x)
                dy (- (az/field state y) stop-y)]
            (set! service-speed (ak/max service-speed speed))
            (set! service-drift (ak/max service-drift (ak/sqrt (+ (* dx dx) (* dy dy))))))
          (set! (az/field control throttle) 0.0)
          (set! (az/field control brake) 1.0))
        (set! error-max (ak/max error-max
          (ak/abs (- (az/field control lane) (track/pit-offset q 6.0)))))
        (set! up (ak/min up (- 1.0 (* 2.0 (+ (* (az/field state qx) (az/field state qx))
                                           (* (az/field state qy) (az/field state qy)))))))
        (when (> progress 1.075) (ak/break))
        (dotimes [_ (ak/divTrunc physics/step-rate 120)]
          (driver/apply! car control)
          (when analytic? (tire-probe/apply-ray-tires-probe! car))
          (physics/step! world))))
    (PitResult {:progress progress :max_error error-max :minimum_up up
                 :stopped_seconds stopped :stop_error stop-error :final_speed speed
                 :service_max_speed service-speed :service_drift service-drift})))

(az/defn driven-pit-probe :- PitResult []
  (driven-pit-contact-probe false))

(defn assert-pit-result [result]
    (is (> (:progress result) 1.075) (pr-str result))
    (is (< (:max_error result) 2.5) (pr-str result))
    (is (> (:minimum_up result) 0.8) (pr-str result))
    (is (>= (:stopped_seconds result) 3.0) (pr-str result))
    (is (< (:stop_error result) 2.0) (pr-str result))
    (is (> (:final_speed result) 10.0) (pr-str result))
    (is (< (:service_max_speed result) 0.02) (pr-str result))
    (is (< (:service_drift result) 0.02) (pr-str result)))

(deftest physical-pit-entry-service-exit-test
  (assert-pit-result (az/value (driven-pit-probe))))

(deftest analytic-pit-entry-service-exit-test
  (assert-pit-result (az/value (driven-pit-contact-probe true))))

(deftest pit-taper-keeps-vehicle-footprint-on-asphalt-test
  ;; Check both fast lane and service route, including the narrowing merges.
  ;; Margins include the actual ~2.5m-wide assembly and tracking tolerance.
  (doseq [i (range 129)]
    (let [p (+ 0.935 (* (/ i 128.0) 0.125))
          edges (mapv #(track/surface-boundary p %) (range 6))
          [_ left right pit-left pit-right _] edges
          paved (if (< (- pit-left right) 1.0e-5)
                  [[left pit-right]] [[left right] [pit-left pit-right]])]
      (is (apply <= edges) (pr-str {:progress p :edges edges}))
      (doseq [work [0.0 6.0]]
        (let [center (track/pit-offset p work)]
          (is (some (fn [[a b]]
                      (and (<= a (+ (- center 2.5) 1.0e-4))
                           (>= b (- (+ center 2.5) 1.0e-4)))) paved)
              (pr-str {:progress p :work work :center center :paved paved})))))))
