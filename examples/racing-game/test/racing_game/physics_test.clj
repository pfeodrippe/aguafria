(ns racing-game.physics-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [racing-game.physics :as physics]
            [racing-game.vehicle-spec :as spec]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.test :refer [deftest is]]))

(a/defstruct ImpactResult {:layout :extern}
  [[:hits :i32] [:minimum_separation :f32] [:max_spin :f32] [:final_speed :f32]
   [:maximum_penetration :f32]])

(deftest tire-contact-work-test
  (let [wear physics/tread-wear-step]
    (is (zero? (wear 2500.0 2000.0 0.0 0.0 60.0)) "Braked waiting is not tread work")
    (is (zero? (wear 2500.0 2000.0 0.01 0.01 60.0)) "Ignore tiny solver rest velocities")
    (is (zero? (wear 0.0 0.0 80.0 80.0 60.0)) "Airborne wheelspin has no road contact work")
    (is (pos? (wear 2500.0 0.0 0.0 60.0 1.0)) "Rolling loaded tires wear")
    (is (> (wear 2500.0 2000.0 30.0 60.0 1.0)
           (wear 2500.0 0.0 0.0 60.0 1.0)) "Locked/sliding tires dissipate more work")
    (is (pos? (wear 2500.0 2000.0 30.0 0.0 1.0)) "Grounded wheelspin wears even at zero chassis speed")
    (is (< (abs (- (* 120.0 (wear 2500.0 2000.0 2.0 60.0 (/ 1.0 120.0)))
                   (wear 2500.0 2000.0 2.0 60.0 1.0))) 1e-8) "Work integrates with timestep")))

(a/defn tread-work-probe [:array 3 :f32]
  "Real Box3D contact/rolling fixture, not invented AI or inferred lane work.
  Mode 0 waits braked, 1 drives on the ground, 2 spins airborne." [[mode :u8]]
  (let [airborne (ak/== mode 2)
        world (physics/create-world (if airborne 0.0 -9.81))
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.78}) 0.0)]
    (ak/defer (physics/destroy-world! world))
    (when (ak/! airborne)
      (let [ground (physics/create-box world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                     (b3/b3Vec3 {:x 200.0 :y 20.0 :z 0.5}) 0.0 0.0)]
        (physics/mark-tire-surface! ground)))
    (dotimes [_ physics/step-rate]
      (physics/drive! car 0.0 1.0 0.0)
      (physics/step! world))
    (ak/= :_ (physics/take-vehicle-tread-loss! car))
    (let [before (physics/body-state (a/field car chassis))]
      (dotimes [_ physics/step-rate]
        (physics/drive! car (if (ak/== mode 0) 0.0 1.0) (if (ak/== mode 0) 1.0 0.0) 0.0)
        (physics/step! world))
      (let [loss (physics/take-vehicle-tread-loss! car)
            after (physics/body-state (a/field car chassis))
            twice (physics/take-vehicle-tread-loss! car)]
        (a/init [loss twice (- (a/field after x) (a/field before x))] [:array 3 :f32])))))

(deftest physical-tread-work-test
  (let [[waiting twice _ :as rest] (a/value (tread-work-probe 0))
        [driven driven-twice distance :as drive] (a/value (tread-work-probe 1))
        [air air-twice _ :as airborne] (a/value (tread-work-probe 2))]
    (prn :tread-contact-results {:waiting rest :driving drive :airborne airborne})
    (is (< waiting 1e-6) (pr-str rest))
    (is (pos? driven) (pr-str drive))
    (is (> distance 0.1) (pr-str drive))
    (is (zero? air) (pr-str airborne))
    (is (every? zero? [twice driven-twice air-twice]) "Consuming work twice must not double-charge wear")))

(a/defn timestep-probe :f32 []
  (let [world (physics/create-world 0.0)
        body (physics/create-box world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.0})
                                 (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0}) 0.0 1.0)]
    (ak/defer (physics/destroy-world! world))
    (b3/b3Body_SetLinearVelocity body (b3/b3Vec3 {:x 1.0 :y 0.0 :z 0.0}))
    (dotimes [_ physics/step-rate] (physics/step! world))
    (a/field (physics/body-state body) x)))

(deftest native-timestep-test
  (is (< (abs (- (timestep-probe) 1.0)) 0.001)
      "Configured collision steps must advance exactly one physical second"))

(a/defn airborne-propulsion-probe [:array 2 :f32]
  "No ground and no gravity: motors may spin wheels, not propel the assembly.
  Track the whole assembly centre of mass, allowing chassis reaction rotation." []
  (let [world (physics/create-world 0.0)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 10.0}) 0.0)
        ^{:var :f32} initial 0.0
        ^{:var :f32} final-position 0.0]
    (ak/defer (physics/destroy-world! world))
    (dotimes [i 4]
      (ak/= initial (+ initial (* 23.0 (a/field (physics/body-state (a/index (a/field car wheels) i)) x)))))
    (dotimes [_ (* 5 physics/step-rate)]
      (physics/drive! car 1.0 0.0 0.0)
      (physics/step! world))
    (ak/= final-position (* 700.0 (a/field (physics/body-state (a/field car chassis)) x)))
    (dotimes [i 4]
      (ak/= final-position (+ final-position (* 23.0 (a/field (physics/body-state (a/index (a/field car wheels) i)) x)))))
    (a/init [(/ (- final-position initial) 792.0)
       (b3/b3WheelJoint_GetSpinSpeed (a/index (a/field car joints) 2))] [:array 2 :f32])))

(deftest propulsion-needs-tire-contact-test
  (let [[travel wheel-spin] (a/value (airborne-propulsion-probe))]
    (is (< (abs travel) 0.05) "Spinning airborne wheels must not directly drive the chassis")
    (is (> (abs wheel-spin) 5.0) "The motor must actually be running during the test")))

(a/defstruct FleetTiming {:layout :extern}
  [[:milliseconds :f32] [:maximum_speed :f32] [:maximum_yaw :f32]])

(a/defn fleet-benchmark FleetTiming
  "One simulated second, eight independent four-wheel cars. Initialization and
  suspension settling are outside the measured native interval." []
  (let [world (physics/create-world -9.81)
        ^:var cars (mem/zeroes (a/type [:array 8 physics/Vehicle]))]
    (ak/defer (physics/destroy-world! world))
    (ak/= :_ (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                               (b3/b3Vec3 {:x 500.0 :y 500.0 :z 0.5}) 0.0))
    (dotimes [i 8]
      (ak/= (a/index cars i)
            (physics/create-vehicle world
              (b3/b3Pos {:x 0.0 :y (* (ak/as (ak/floatFromInt i) :f32) 6.0) :z 0.70}) 0.0)))
    (dotimes [_ physics/step-rate] (physics/step! world))
    (let [started (b3/b3GetTicks)
          ^{:var :f32} speed 0.0
          ^{:var :f32} yaw 0.0]
      (dotimes [_ physics/step-rate]
        (dotimes [i 8] (physics/drive! (a/index cars i) 1.0 0.0 0.0))
        (physics/step! world))
      (let [elapsed (b3/b3GetMilliseconds started)]
        (dotimes [i 8]
          (let [state (physics/body-state (a/field (a/index cars i) chassis))]
            (ak/= speed (ak/max speed (a/field state vx)))
            (ak/= yaw (ak/max yaw (ak/abs (a/field state qz))))))
        (FleetTiming {:milliseconds elapsed :maximum_speed speed :maximum_yaw yaw})))))

(a/defn impact-probe ImpactResult [[offset :f32]]
  (let [world (physics/create-world 0.0)
        size (b3/b3Vec3 {:x 2.5 :y 0.75 :z 0.20})
        a (physics/create-box world (b3/b3Pos {:x -10.0 :y 0.0 :z 0.0}) size 0.0 700.0)
        b (physics/create-box world (b3/b3Pos {:x 10.0 :y offset :z 0.0}) size 0.0 700.0)
        ^{:var :i32} hits 0
        ^{:var :f32} minimum 20.0
        ^{:var :f32} penetration 0.0
        ^:var contacts (mem/zeroes (a/type [:array 8 b3/b3ContactData]))
        ^{:var :f32} spin 0.0]
    (ak/defer (physics/destroy-world! world))
    (b3/b3Body_SetLinearVelocity a (b3/b3Vec3 {:x 83.333333 :y 0.0 :z 0.0}))
    (b3/b3Body_SetLinearVelocity b (b3/b3Vec3 {:x -83.333333 :y 0.0 :z 0.0}))
    (dotimes [_ physics/step-rate]
      (physics/step! world)
      (let [count (b3/b3Body_GetContactData a (ak/& contacts) 8)]
        (dotimes [i count]
          (let [contact (a/index contacts i)]
            (dotimes [j (a/field contact manifoldCount)]
              (let [manifold (a/index (a/field contact manifolds) j)]
                (dotimes [k (a/field manifold pointCount)]
                  (ak/= penetration
                        (ak/max penetration
                                (- (a/field (a/index (a/field manifold points) k) separation))))))))))
      (let [sa (physics/body-state a) sb (physics/body-state b)]
        (ak/= hits (+ hits (a/field (b3/b3World_GetContactEvents world) hitCount)))
        (ak/= minimum (ak/min minimum (- (a/field sb x) (a/field sa x))))
        (ak/= spin (ak/max spin (ak/abs (a/field sa wz))))))
    (ImpactResult {:hits hits :minimum_separation minimum :max_spin spin
                   :final_speed (a/field (physics/body-state a) vx)
                   :maximum_penetration penetration})))

(a/defstruct VehicleResult {:layout :extern}
  [[:speed_before_braking :f32] [:speed_after_braking :f32]
   [:distance :f32] [:height :f32] [:wheel_spin :f32] [:yaw :f32]])

(a/defn tire-lifecycle-probe [:array 8 :f32]
  "Two independent worlds: support without controls, no cross-world stepping,
  destroyed body generations, and a detached tire resting on its endcap.
  The single transform write establishes the endcap test's INITIAL pose." []
  (let [a (physics/create-world -9.81)
        b (physics/create-world -9.81)
        car-a (physics/create-vehicle a (b3/b3Pos {:x 0.0 :y 0.0 :z 0.7}) 0.0)
        car-b (physics/create-vehicle b (b3/b3Pos {:x 0.0 :y 0.0 :z 0.7}) 0.0)]
    (ak/defer (physics/destroy-world! a))
    (ak/defer (physics/destroy-world! b))
    (ak/= :_ (physics/create-ground a (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
              (b3/b3Vec3 {:x 100.0 :y 100.0 :z 0.5}) 0.0))
    (ak/= :_ (physics/create-ground b (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
              (b3/b3Vec3 {:x 100.0 :y 100.0 :z 0.5}) 0.0))
    (dotimes [_ (* 3 physics/step-rate)]
      (physics/step! a)
      (physics/step! b))
    (let [settled (physics/body-state (a/field car-a chassis))
          untouched (physics/body-state (a/field car-b chassis))]
      (dotimes [_ (* 2 physics/step-rate)]
        (physics/drive! car-a 1.0 0.0 0.0)
        (physics/step! a))
      (let [moving (physics/body-state (a/field car-a chassis))
            still (physics/body-state (a/field car-b chassis))
            wheel (a/index (a/field car-b wheels) 0)
            p (b3/b3Body_GetPosition wheel)]
        (b3/b3DestroyBody (a/index (a/field car-a wheels) 0))
        (ak/= :_ (physics/create-vehicle a (b3/b3Pos {:x 30.0 :y 0.0 :z 0.7}) 0.0))
        ;; No chassis ownership shortcut: the four tires remain real bodies.
        (b3/b3DestroyBody (a/field car-b chassis))
        (b3/b3Body_SetTransform wheel
          (b3/b3Pos {:x (a/field p x) :y (a/field p y) :z 1.0})
          (b3/b3MakeQuatFromAxisAngle (b3/b3Vec3 {:x 1.0 :y 0.0 :z 0.0}) 1.57079632679))
        (dotimes [_ (* 3 physics/step-rate)]
          (physics/step! a)
          (physics/step! b))
        (let [endcap (physics/body-state wheel)
              state-a (a/cast (b3/b3World_GetUserData a) [:* physics/WorldState])
              state-b (a/cast (b3/b3World_GetUserData b) [:* physics/WorldState])]
          (a/init [(a/field settled z) (a/field settled vz)
             (- (a/field moving x) (a/field settled x))
             (+ (ak/abs (- (a/field still x) (a/field untouched x)))
                (ak/abs (- (a/field still z) (a/field untouched z))))
             (a/field endcap z) (a/field endcap vz)
             (ak/as (ak/floatFromInt (a/field state-a count)) :f32)
             (ak/as (ak/floatFromInt (a/field state-b count)) :f32)] [:array 8 :f32]))))))

(deftest tire-lifecycle-test
  (let [[height vz travel other-world-change cap-height cap-vz count-a count-b :as result]
        (a/value (tire-lifecycle-probe))]
    (is (< 0.4 height 0.8) (pr-str result))
    (is (< (abs vz) 0.1) (pr-str result))
    (is (> travel 10.0) (pr-str result))
    (is (zero? other-world-change) (pr-str result))
    (is (< 0.22 cap-height 0.28) (pr-str result))
    (is (< (abs cap-vz) 0.1) (pr-str result))
    (is (= 7.0 count-a) (pr-str result))
    (is (= 4.0 count-b) (pr-str result))))

(a/defn tire-surface-boundary-probe [:array 5 :f32]
  "A detached production tire on banked or finite ground. Only initial pose
  and rolling impulses are set; all subsequent support/falling is force-driven.
  The finite platform ends at x=3m: its contact plane must not extend to infinity." [[finite? :bool] [bank :f32]]
  (let [world (physics/create-world -9.81)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.7}) 0.0)
        tire (a/index (a/field car wheels) 0)
        radius (a/index (a/index spec/wheel-geometry 0) 3)
        normal (b3/b3Vec3 {:x (ak/sin bank) :y 0.0 :z (ak/cos bank)})
        center (b3/b3Pos {:x (* -0.5 (a/field normal x)) :y 0.0
                          :z (* -0.5 (a/field normal z))})
        ground (physics/create-ground world center
                 (b3/b3Vec3 {:x (if finite? (ak/as 3.0 :f32) 200.0) :y 20.0 :z 0.5}) 0.0)]
    (ak/defer (physics/destroy-world! world))
    (b3/b3DestroyBody (a/field car chassis))
    (dotimes [i 3] (b3/b3DestroyBody (a/index (a/field car wheels) (+ i 1))))
    (b3/b3Body_SetTransform ground center
      (b3/b3MakeQuatFromAxisAngle (b3/b3Vec3 {:x 0.0 :y 1.0 :z 0.0}) bank))
    (b3/b3Body_SetTransform tire
      (b3/b3Pos {:x 0.0 :y 0.0 :z (+ radius 0.05)})
      (b3/b3MakeQuatFromAxisAngle (b3/b3Vec3 {:x 0.0 :y 1.0 :z 0.0}) 0.0))
    (when finite?
      (let [mass (b3/b3Body_GetMassData tire)]
        (b3/b3Body_ApplyLinearImpulseToCenter tire
          (b3/b3Vec3 {:x (* (a/field mass mass) 6.0) :y 0.0 :z 0.0}) true)
        (b3/b3Body_ApplyAngularImpulse tire
          (b3/b3MulMV (a/field mass inertia)
            (b3/b3Vec3 {:x 0.0 :y (/ 6.0 radius) :z 0.0})) true)))
    (dotimes [_ (if finite? (ak/divTrunc (* 3 physics/step-rate) 2)
                           (* 4 physics/step-rate))]
      (physics/step! world))
    (let [p (physics/body-state tire)]
      (a/init [(a/field p x) (a/field p z) (a/field p vz)
         (+ (* (a/field p x) (a/field normal x)) (* (a/field p z) (a/field normal z)))
         radius] [:array 5 :f32]))))

(deftest production-tires-respect-surface-boundaries-test
  (let [[x z vz _ _ :as result] (a/value (tire-surface-boundary-probe true 0.0))]
    (is (> x 4.0) (pr-str result))
    (is (< z -1.0) "Leaving the finite ground must fall, not hover on an infinite contact plane")
    (is (< vz -3.0) (pr-str result)))
  (doseq [bank [0.12 -0.12]]
    (let [[x _ _ clearance radius :as result]
          (a/value (tire-surface-boundary-probe false bank))]
      (is (> (* x bank) 0.5) "An unpowered tire must roll downhill under gravity")
      (is (< (abs (- clearance radius)) 0.04) (pr-str result)))))

(a/defn tire-barrier-probe [:array 3 :f32]
  "Accelerate into a real wall. Offset mode initially contacts only the tire;
  full-width mode must stop forward travel. No velocity or pose corrections." [[offset? :bool]]
  (let [world (physics/create-world -9.81)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.7}) 0.0)
        wall (physics/create-box world
               (b3/b3Pos {:x 18.0 :y (if offset? (ak/as 1.16 :f32) 0.0) :z 1.0})
               (b3/b3Vec3 {:x 0.3 :y (if offset? (ak/as 0.10 :f32) 20.0) :z 1.0}) 0.0 0.0)
        ^{:var :f32} tire-hits 0.0
        ^{:var :f32} maximum-speed 0.0
        ^{:var :f32} maximum-x 0.0]
    (ak/defer (physics/destroy-world! world))
    (ak/= :_ (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
              (b3/b3Vec3 {:x 100.0 :y 100.0 :z 0.5}) 0.0))
    (dotimes [_ physics/step-rate] (physics/step! world))
    (dotimes [_ (* 5 physics/step-rate)]
      (physics/drive! car 1.0 0.0 0.0)
      (physics/step! world)
      (let [state (physics/body-state (a/field car chassis))
            events (b3/b3World_GetContactEvents world)]
        (ak/= maximum-speed (ak/max maximum-speed (a/field state vx)))
        (ak/= maximum-x (ak/max maximum-x (a/field state x)))
        (dotimes [j (a/field events hitCount)]
          (let [hit (a/index (a/field events hitEvents) j)
                a (b3/b3Shape_GetBody (a/field hit shapeIdA))
                b (b3/b3Shape_GetBody (a/field hit shapeIdB))]
            (dotimes [i 4]
              (let [wheel (a/index (a/field car wheels) i)]
                (when (or (and (ak/== (b3/b3StoreBodyId a) (b3/b3StoreBodyId wheel))
                               (ak/== (b3/b3StoreBodyId b) (b3/b3StoreBodyId wall)))
                          (and (ak/== (b3/b3StoreBodyId b) (b3/b3StoreBodyId wheel))
                               (ak/== (b3/b3StoreBodyId a) (b3/b3StoreBodyId wall))))
                  (ak/+= tire-hits 1.0))))))))
    (a/init [tire-hits maximum-speed maximum-x] [:array 3 :f32])))

(deftest tire-barrier-contact-test
  (let [[hits speed _ :as offset] (a/value (tire-barrier-probe true))
        [_ head-speed distance :as head-on] (a/value (tire-barrier-probe false))]
    (is (pos? hits) (pr-str offset))
    (is (> speed 5.0) (pr-str offset))
    (is (> head-speed 5.0) (pr-str head-on))
    (is (< distance 18.0) (pr-str head-on))))

(a/defn vehicle-state-probe [:array 5 physics/BodyState]
  "Inspect the full native assembly after settling and optional throttle ticks." [[ticks :u32]]
  (let [world (physics/create-world -9.81)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.70}) 0.0)
        ^:var result (mem/zeroes (a/type [:array 5 physics/BodyState]))]
    (ak/defer (physics/destroy-world! world))
    (ak/= :_ (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                               (b3/b3Vec3 {:x 500.0 :y 500.0 :z 0.5}) 0.0))
    (dotimes [_ (* 2 physics/step-rate)] (physics/step! world))
    (dotimes [_ ticks]
      (physics/drive! car 1.0 0.0 0.0)
      (physics/step! world))
    (ak/= (a/index result 0) (physics/body-state (a/field car chassis)))
    (dotimes [i 4]
      (ak/= (a/index result (+ i 1)) (physics/body-state (a/index (a/field car wheels) i))))
    result))

(a/defn straight-line-drive-trace [:array 10 [:array 8 :f32]]
  "Ten seconds of full throttle on flat ground. Rows: elapsed seconds,
  forward/world-X speed, lateral/world-Y speed, mean delivered rear motor
  torque L/R, rear spin L/R, chassis height. No velocity/pose writes." []
  (let [world (physics/create-world -9.81)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.70}) 0.0)
        ^:var result (mem/zeroes (a/type [:array 10 [:array 8 :f32]]))]
    (ak/defer (physics/destroy-world! world))
    (ak/= :_ (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                               (b3/b3Vec3 {:x 500.0 :y 500.0 :z 0.5}) 0.0))
    (dotimes [_ (* 2 physics/step-rate)] (physics/step! world))
    (dotimes [second 10]
      (let [^{:var :f32} left-torque 0.0
            ^{:var :f32} right-torque 0.0
            left (a/index (a/field car joints) 2)
            right (a/index (a/field car joints) 3)]
        (dotimes [_ physics/step-rate]
          (physics/drive! car 1.0 0.0 0.0)
          (physics/step! world)
          (ak/+= left-torque (b3/b3WheelJoint_GetSpinTorque left))
          (ak/+= right-torque (b3/b3WheelJoint_GetSpinTorque right)))
        (let [state (physics/body-state (a/field car chassis))
              count (ak/as (ak/floatFromInt physics/step-rate) :f32)]
          (ak/= (a/index result second)
                (a/init [(ak/as (ak/floatFromInt (+ second 1)) :f32)
                   (a/field state vx) (a/field state vy)
                   (/ left-torque count) (/ right-torque count)
                   (b3/b3WheelJoint_GetSpinSpeed left)
                   (b3/b3WheelJoint_GetSpinSpeed right) (a/field state z)] [:array 8 :f32])))))
    result))

(deftest straight-line-drive-measurements-test
  (let [rows (a/value (straight-line-drive-trace))]
    (is (= 10 (count rows)))
    (is (every? #(Double/isFinite (double %)) (mapcat identity rows)))
    (is (apply < (map second rows)) "Fixed full throttle must continue accelerating in this fixture")
    (is (every? #(<= (abs %) (+ 1.0 physics/rear-wheel-torque-nm))
                (mapcat #(subvec (vec %) 3 5) rows))
        "Measured average motor torque stays within the configured shaft limit")))

(a/defn wheel-motor-torque-probe [:array 3 :f32]
  "An airborne wheel on a static axle: angular momentum must equal torque*time.
  No tire contact, gravity, steering command, or vehicle controller can supply
  additional energy. Compare warm starting and steerable/non-steerable joints." [[warm-start :bool] [steerable :bool]]
  (let [world (physics/create-world 0.0)
        ^:var fixed-def (b3/b3DefaultBodyDef)
        axle (b3/b3CreateBody world (ak/& fixed-def))
        ^:var wheel-def (b3/b3DefaultBodyDef)
        shape-def (b3/b3DefaultShapeDef)
        sphere (b3/b3Sphere {:center {:x 0.0 :y 0.0 :z 0.0} :radius 0.5})
        ^:var joint-def (b3/b3DefaultWheelJointDef)]
    (ak/defer (physics/destroy-world! world))
    (b3/b3World_EnableWarmStarting world warm-start)
    (ak/= (a/field wheel-def type) b3/b3_dynamicBody)
    (ak/= (a/field wheel-def allowFastRotation) true)
    (let [wheel (b3/b3CreateBody world (ak/& wheel-def))]
      (ak/= :_ (b3/b3CreateSphereShape wheel (ak/& shape-def) (ak/& sphere)))
      (ak/= (a/field (a/field joint-def base) bodyIdA) axle)
      (ak/= (a/field (a/field joint-def base) bodyIdB) wheel)
      (ak/= (a/field joint-def enableSteering) steerable)
      (ak/= (a/field joint-def enableSpinMotor) true)
      (ak/= (a/field joint-def maxSpinTorque) 1.0)
      (ak/= (a/field joint-def spinSpeed) 100.0)
      (let [joint (b3/b3CreateWheelJoint world (ak/& joint-def))]
        (dotimes [_ 384] (physics/step! world))
        (let [mass (b3/b3Body_GetMassData wheel)
              omega (b3/b3Body_GetAngularVelocity wheel)]
          (a/init [(* (a/field (a/field (a/field mass inertia) cz) z)
                (a/field omega z))
             (* 384.0 physics/fixed-step)
             (b3/b3WheelJoint_GetSpinTorque joint)] [:array 3 :f32]))))))

(deftest wheel-motor-conserves-angular-impulse-test
  (doseq [warm [false true] steerable [false true]]
    (let [[measured expected reported] (a/value (wheel-motor-torque-probe warm steerable))]
      (is (< (abs (- measured expected)) 0.001)
          (str "Actual angular impulse must obey torque*time, warm=" warm
               ", steerable=" steerable "; got " measured " vs " expected))
      (is (< (abs (- reported 1.0)) 0.001)
          "Torque reporting alone cannot establish physical correctness"))))

(a/defn drivetrain-candidate! :void
  "Isolated experiment, never used by the live driver. Mode 0 is production;
  1 widens its rolling-speed servo allowance to32rad/s; 2 applies an equal/opposite
  shaft torque to each rear wheel and chassis; 3 uses a high servo target.
  Modes4/5/6/7 use allowances1/2/4/12rad/s respectively.
  All modes retain the same torque/power bounds and production aero forces.
  No body position, velocity, or rotation is assigned." [[car physics/Vehicle] [mode :u8]]
  (physics/drive! car (if (ak/== mode 0) (ak/as 1.0 :f32) 0.0) 0.0 0.0)
  (when (> mode 0)
    (let [chassis (a/field car chassis)
          forward (b3/b3RotateVector (b3/b3Body_GetRotation chassis)
                    (b3/b3Vec3 {:x 1.0 :y 0.0 :z 0.0}))]
      (dotimes [rear 2]
        (let [i (+ rear 2)
              wheel (a/index (a/field car wheels) i)
              joint (a/index (a/field car joints) i)
              spin (b3/b3WheelJoint_GetSpinSpeed joint)
              torque (if (< spin 260.0)
                       (ak/min physics/rear-wheel-torque-nm
                         (/ physics/engine-power-watts
                            (* 2.0 (ak/max 10.0 (ak/abs spin)))))
                       (ak/as 0.0 :f32))]
          (if (ak/== mode 2)
            (let [axis (b3/b3RotateVector (b3/b3Body_GetRotation wheel)
                         (b3/b3Vec3 {:x 0.0 :y 1.0 :z 0.0}))
                  shaft (b3/b3Vec3 {:x (* torque (a/field axis x))
                                   :y (* torque (a/field axis y))
                                   :z (* torque (a/field axis z))})]
              (b3/b3WheelJoint_EnableSpinMotor joint false)
              (b3/b3Body_ApplyTorque wheel shaft true)
              (b3/b3Body_ApplyTorque chassis
                (b3/b3Vec3 {:x (- (a/field shaft x))
                           :y (- (a/field shaft y))
                           :z (- (a/field shaft z))}) true))
            (let [v (b3/b3Body_GetLinearVelocity wheel)
                  speed (+ (* (a/field v x) (a/field forward x))
                           (* (a/field v y) (a/field forward y))
                           (* (a/field v z) (a/field forward z)))
                  radius (a/index (a/index spec/wheel-geometry i) 3)
                  ^{:zig/type :f32} allowance
                  (cond (ak/== mode 4) 1.0 (ak/== mode 5) 2.0
                        (ak/== mode 6) 4.0 (ak/== mode 7) 12.0 :else 32.0)]
              (b3/b3WheelJoint_SetMaxSpinTorque joint torque)
              (b3/b3WheelJoint_SetSpinMotorSpeed joint
                (if (ak/== mode 3) (ak/as 260.0 :f32)
                  (ak/min 260.0 (+ (ak/max 0.0 (/ speed radius)) allowance)))))))))))

(a/defn diagnostic-tire-shape! :void
  "Isolate contact geometry, keeping the original wheel mass and inertia.
  Shape0 leaves production cylinders intact. Shape1 uses the upstream sample's
  smooth sphere approach (too wide for production). Shape2 is a 32-sphere
  rounded tread ring with the authored outer radius and width. Neither changes
  the visual model or live vehicle. Shape3 uses a42-sided convex hull, the
  largest whole cylinder within upstream's128-edge limit. Shape4 crowns the
  tread by3cm using three24-point rings, retaining outer radius and width.
  Diagnostic only." [[car physics/Vehicle] [shape-kind :u8]]
  (when (> shape-kind 0)
    (dotimes [i 4]
      (let [wheel (a/index (a/field car wheels) i)
            mass (b3/b3Body_GetMassData wheel)
            dimensions (a/index spec/wheel-geometry i)
            radius (a/index dimensions 3)
            half-width (* 0.5 (a/index dimensions 4))
            ^:var shapes (mem/zeroes (a/type [:array 1 b3/b3ShapeId]))
            count (b3/b3Body_GetShapes wheel (ak/& shapes) 1)
            ^:var definition (b3/b3DefaultShapeDef)]
        (when (ak/== count 1)
          (b3/b3DestroyShape (a/index shapes 0) false)
          (ak/= (a/field (a/field definition baseMaterial) friction) physics/tire-friction)
          (ak/= (a/field (a/field definition baseMaterial) restitution) 0.0)
          (cond
            (ak/== shape-kind 1)
            (let [sphere (b3/b3Sphere {:center {:x 0.0 :y 0.0 :z 0.0} :radius radius})]
              (ak/= :_ (b3/b3CreateSphereShape wheel (ak/& definition) (ak/& sphere))))

            (ak/== shape-kind 3)
            (let [^:var vertices (mem/zeroes (a/type [:array 84 b3/b3Vec3]))]
              (dotimes [part 42]
                (let [angle (* 6.28318530718 (/ (ak/as (ak/floatFromInt part) :f32) 42.0))
                      x (* radius (ak/cos angle))
                      z (* radius (ak/sin angle))]
                  (ak/= (a/index vertices (* 2 part))
                    (b3/b3Vec3 {:x x :y (- half-width) :z z}))
                  (ak/= (a/index vertices (+ (* 2 part) 1))
                    (b3/b3Vec3 {:x x :y half-width :z z}))))
              (let [hull (b3/b3CreateHull (ak/& vertices) 84 84)]
                (ak/defer (b3/b3DestroyHull hull))
                (ak/= :_ (b3/b3CreateHullShape wheel (ak/& definition) hull))))

            (ak/== shape-kind 4)
            (let [^:var vertices (mem/zeroes (a/type [:array 72 b3/b3Vec3]))]
              (dotimes [ring 3]
                (dotimes [part 24]
                  (let [angle (* 6.28318530718 (/ (ak/as (ak/floatFromInt part) :f32) 24.0))
                        r (if (ak/== ring 1) radius (- radius 0.03))
                        y (* (- (ak/as (ak/floatFromInt ring) :f32) 1.0) half-width)]
                    (ak/= (a/index vertices (+ (* ring 24) part))
                      (b3/b3Vec3 {:x (* r (ak/cos angle)) :y y :z (* r (ak/sin angle))})))))
              (let [hull (b3/b3CreateHull (ak/& vertices) 72 72)]
                (ak/defer (b3/b3DestroyHull hull))
                (ak/= :_ (b3/b3CreateHullShape wheel (ak/& definition) hull))))

            :else
            (dotimes [part 32]
              (let [angle (* 6.28318530718 (/ (ak/as (ak/floatFromInt part) :f32) 32.0))
                    centre-radius (- radius half-width)
                    sphere (b3/b3Sphere
                             {:center {:x (* centre-radius (ak/cos angle))
                                       :y 0.0
                                       :z (* centre-radius (ak/sin angle))}
                              :radius half-width})]
                (ak/= :_ (b3/b3CreateSphereShape wheel (ak/& definition) (ak/& sphere))))))
          (b3/b3Body_SetMassData wheel mass))))))

(a/defn tread-plane-contact-probe! :void
  "TEST ONLY: two analytic tread-edge contacts against a static surface plane.
  Normal spring/damping and Coulomb-limited slip forces act at the contact
  points; Box3D integrates translation, spin and reactions through suspension.
  No track lookup, body pose/velocity writes, or artificial upright constraint.
  This flat-fixture experiment does not implement arbitrary terrain contacts." [[wheel b3/b3BodyId] [radius :f32] [half-width :f32]
            [surface-normal b3/b3Vec3] [surface-point b3/b3Pos] [surface-friction :f32]]
  (let [position (b3/b3Body_GetPosition wheel)
        axis (b3/b3RotateVector (b3/b3Body_GetRotation wheel)
               (b3/b3Vec3 {:x 0.0 :y 1.0 :z 0.0}))
        vertical (b3/b3Dot axis surface-normal)
        radial-length (ak/sqrt (ak/max 0.0 (- 1.0 (* vertical vertical))))
        mass (b3/b3Body_GetMassData wheel)
        inverse-inertia (b3/b3Body_GetWorldInverseRotationalInertia wheel)]
    (when (> radial-length 0.05)
      (dotimes [edge 2]
        (let [side (* half-width (if (ak/== edge 0) (ak/as -1.0 :f32) 1.0))
              r (b3/b3Vec3
                  {:x (+ (* (/ radius radial-length) (- (* vertical (a/field axis x)) (a/field surface-normal x))) (* side (a/field axis x)))
                   :y (+ (* (/ radius radial-length) (- (* vertical (a/field axis y)) (a/field surface-normal y))) (* side (a/field axis y)))
                   :z (+ (* (/ radius radial-length) (- (* vertical (a/field axis z)) (a/field surface-normal z))) (* side (a/field axis z)))})
              point (b3/b3Pos {:x (+ (a/field position x) (a/field r x))
                               :y (+ (a/field position y) (a/field r y))
                               :z (+ (a/field position z) (a/field r z))})
              velocity (b3/b3Body_GetWorldPointVelocity wheel point)
              normal-speed (b3/b3Dot velocity surface-normal)
              tangent (b3/b3Sub velocity (b3/b3MulSV normal-speed surface-normal))
              penetration (+ (* (- (a/field surface-point x) (a/field point x)) (a/field surface-normal x))
                             (* (- (a/field surface-point y) (a/field point y)) (a/field surface-normal y))
                             (* (- (a/field surface-point z) (a/field point z)) (a/field surface-normal z)))
              frequency (* 6.28318530718 60.0)
              edge-mass (* 0.5 (a/field mass mass))
              normal (if (> penetration 0.0)
                       (ak/max 0.0 (- (* edge-mass frequency frequency penetration)
                                       (* 2.0 edge-mass frequency normal-speed)))
                       (ak/as 0.0 :f32))
              slip (ak/sqrt (b3/b3Dot tangent tangent))
              direction (b3/b3MulSV (/ 1.0 (ak/max slip 0.000001)) tangent)
              arm (b3/b3Cross r direction)
              inverse-mass (+ (/ 1.0 (a/field mass mass))
                              (b3/b3Dot arm (b3/b3MulMV inverse-inertia arm)))
              ;; Two edge forces share the same wheel's slip impulse budget.
              friction (ak/min (* (ak/sqrt (* physics/tire-friction surface-friction)) normal)
                           (/ slip (* 2.0 physics/fixed-step inverse-mass)))]
          (b3/b3Body_ApplyForce wheel
            (b3/b3Sub (b3/b3MulSV normal surface-normal)
                      (b3/b3MulSV friction direction)) point true))))))

(a/defn flat-tire-contact-probe! :void
  [[wheel b3/b3BodyId] [radius :f32] [half-width :f32]]
  (tread-plane-contact-probe! wheel radius half-width
    (b3/b3Vec3 {:x 0.0 :y 0.0 :z 1.0})
    (b3/b3Pos {:x 0.0 :y 0.0 :z 0.0}) 0.8))

(a/defn use-flat-tire-probe! :void
  "TEST ONLY: disable wheel colliders in a plane-only fixture, keeping chassis
  contact, mass/inertia and all joints. Never apply this to a live race world." [[car physics/Vehicle]]
  (dotimes [i 4]
    (let [wheel (a/index (a/field car wheels) i)
          ^:var shapes (mem/zeroes (a/type [:array 1 b3/b3ShapeId]))]
      (physics/set-tire-contact-enabled! wheel false)
      (when (ak/== (b3/b3Body_GetShapes wheel (ak/& shapes) 1) 1)
        (let [shape (a/index shapes 0)
              ^:var filter (b3/b3Shape_GetFilter shape)]
          (ak/= (a/field filter maskBits) 0)
          (b3/b3Shape_SetFilter shape filter true))))))

(a/defn apply-flat-tires-probe! :void [[car physics/Vehicle]]
  (dotimes [i 4]
    (let [dimensions (a/index spec/wheel-geometry i)]
      (flat-tire-contact-probe! (a/index (a/field car wheels) i)
        (a/index dimensions 3) (* 0.5 (a/index dimensions 4))))))

(a/defn use-ray-tires-probe! :void
  "TEST ONLY: fixture terrain is category1, cars category2. Wheel hulls still
  collide with other cars, while tire forces handle the static road surface.
  These fixture categories must not be applied to a live game with barriers." [[car physics/Vehicle]]
  ;; Identify the explicitly marked production road BEFORE moving fixture car
  ;; shapes to category2. Otherwise the query can hit the car or miss the road
  ;; after the production category migration, silently testing faceted contacts.
  (let [p (b3/b3Body_GetPosition (a/field car chassis))
        ^:var query (b3/b3DefaultQueryFilter)]
    (ak/= (a/field query maskBits) physics/tire-surface-category)
    (let [hit (b3/b3World_CastRayClosest (b3/b3Body_GetWorld (a/field car chassis))
                (b3/b3Pos {:x (a/field p x) :y (a/field p y) :z (+ (a/field p z) 2.0)})
                (b3/b3Vec3 {:x 0.0 :y 0.0 :z -10.0}) query)]
      (if (a/field hit hit)
        (let [shape (a/field hit shapeId)
              ^:var filter (b3/b3Shape_GetFilter shape)]
          (if (and (ak/== (a/field filter categoryBits) physics/tire-surface-category)
                      (ak/== (b3/b3Body_GetType (b3/b3Shape_GetBody shape)) b3/b3_staticBody))
            (do
              (ak/= (a/field filter categoryBits) 1)
              (b3/b3Shape_SetFilter shape filter true))
            (ak/panic "Ray-tire comparison requires an explicitly marked static road")))
        (ak/panic "Ray-tire comparison could not find its road"))))
  (dotimes [i 5]
    (let [body (if (ak/== i 4) (a/field car chassis) (a/index (a/field car wheels) i))
          ^:var shapes (mem/zeroes (a/type [:array 1 b3/b3ShapeId]))]
      (when (< i 4) (physics/set-tire-contact-enabled! body false))
      (when (ak/== (b3/b3Body_GetShapes body (ak/& shapes) 1) 1)
        (let [shape (a/index shapes 0)
              ^:var filter (b3/b3Shape_GetFilter shape)]
          (ak/= (a/field filter categoryBits) 2)
          (when (< i 4) (ak/= (a/field filter maskBits) 2))
          (b3/b3Shape_SetFilter shape filter true))))))

(a/defn apply-ray-tires-probe! :void
  "TEST ONLY: sample real Box3D road triangles, normals and material friction.
  No circuit progress/height lookup. Missing ground produces no tire force." [[car physics/Vehicle]]
  (let [world (b3/b3Body_GetWorld (a/field car chassis))
        ^:var query (b3/b3DefaultQueryFilter)]
    (ak/= (a/field query maskBits) 1)
    (dotimes [i 4]
      (let [wheel (a/index (a/field car wheels) i)
            dimensions (a/index spec/wheel-geometry i)
            radius (a/index dimensions 3)
            p (b3/b3Body_GetPosition wheel)
            hit (b3/b3World_CastRayClosest world
                  (b3/b3Pos {:x (a/field p x) :y (a/field p y) :z (+ (a/field p z) radius)})
                  (b3/b3Vec3 {:x 0.0 :y 0.0 :z (- (+ (* 2.0 radius) 0.05))}) query)]
        (when (a/field hit hit)
          (let [shape (a/field hit shapeId)
                ^:var material (b3/b3Shape_GetSurfaceMaterial shape)]
            (when (and (ak/== (b3/b3Shape_GetType shape) b3/b3_meshShape)
                       (>= (a/field hit triangleIndex) 0))
              (let [mesh (b3/b3Shape_GetMesh shape)
                    indices (b3/b3GetMeshMaterialIndices (a/field mesh data))]
                (when (ak/!= indices ak/null)
                  (ak/= material (b3/b3Shape_GetMeshSurfaceMaterial shape
                    (a/index indices (ak/as (ak/intCast (a/field hit triangleIndex)) :usize)))))))
            (tread-plane-contact-probe! wheel radius (* 0.5 (a/index dimensions 4))
              (a/field hit normal) (a/field hit point) (a/field material friction))))))))

(a/defn drivetrain-contact-trace [:array 10 [:array 10 :f32]]
  "Ten seconds, fixed full throttle, independent world. Rows contain elapsed
  seconds, world-X velocity, world-Y velocity, X/Y travel, chassis height,
  chassis-up Z, angular-Z velocity, and rear wheel spin L/R (SI units).
  This is a diagnostic, not permission to accept a faster but unstable car."
  [[mode :u8] [shape-kind :u8] [contact-hertz :f32] [contact-damping :f32]]
  (let [world (physics/create-world -9.81)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.70}) 0.0)
        ^:var rows (mem/zeroes (a/type [:array 10 [:array 10 :f32]]))]
    (ak/defer (physics/destroy-world! world))
    (let [defaults (b3/b3DefaultWorldDef)]
      (b3/b3World_SetContactTuning world contact-hertz
        contact-damping (a/field defaults contactSpeed)))
    (if (ak/== shape-kind 5)
      (use-flat-tire-probe! car)
      (diagnostic-tire-shape! car shape-kind))
    (ak/= :_ (physics/create-box world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
              ;; Faster candidates must not drive off the measurement fixture.
              (b3/b3Vec3 {:x 2000.0 :y 2000.0 :z 0.5}) 0.0 0.0))
    (dotimes [_ (* 2 physics/step-rate)]
      (when (ak/== shape-kind 5) (apply-flat-tires-probe! car))
      (physics/step! world))
    (dotimes [second 10]
      (dotimes [_ physics/step-rate]
        (drivetrain-candidate! car mode)
        (when (ak/== shape-kind 5) (apply-flat-tires-probe! car))
        (physics/step! world))
      (let [state (physics/body-state (a/field car chassis))
            up (b3/b3RotateVector (b3/b3Body_GetRotation (a/field car chassis))
                 (b3/b3Vec3 {:x 0.0 :y 0.0 :z 1.0}))]
        (ak/= (a/index rows second)
          (a/init [(ak/as (ak/floatFromInt (+ second 1)) :f32)
             (a/field state vx) (a/field state vy)
             (a/field state x) (a/field state y) (a/field state z)
             (a/field up z) (a/field state wz)
             (b3/b3WheelJoint_GetSpinSpeed (a/index (a/field car joints) 2))
             (b3/b3WheelJoint_GetSpinSpeed (a/index (a/field car joints) 3))] [:array 10 :f32]))))
    rows))

(a/defn drivetrain-comparison-trace [:array 10 [:array 10 :f32]]
  "Fixed-production contact stiffness for tire/motor diagnostic comparisons." [[mode :u8] [shape-kind :u8]]
  (drivetrain-contact-trace mode shape-kind 120.0 10.0))

(a/defn free-rolling-contact-trace [:array 11 [:array 8 :f32]]
  "Contact-only control experiment: no chassis, joints, engine or aero.
  Give one settled wheel matching linear/angular impulses ONCE, then coast.
  Sphere comparison retains cylinder mass/inertia; it is not production art
  or a proposed collider replacement. Rows: seconds, vx, vy, spin-Y, height,
  lateral displacement, angular-X and angular-Z, all in SI units." [[shape-kind :u8] [initial-speed :f32]]
  (let [world (physics/create-world -9.81)
        dimensions (a/index spec/wheel-geometry 2)
        radius (a/index dimensions 3)
        width (a/index dimensions 4)
        hull (b3/b3CreateCylinder width radius (* -0.5 width) 32)
        ^:var definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)
        ^:var rows (mem/zeroes (a/type [:array 11 [:array 8 :f32]]))]
    (ak/defer (b3/b3DestroyHull hull))
    (ak/defer (physics/destroy-world! world))
    (ak/= :_ (physics/create-box world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
              (b3/b3Vec3 {:x 2000.0 :y 2000.0 :z 0.5}) 0.0 0.0))
    (ak/= (a/field definition type) b3/b3_dynamicBody)
    (ak/= (a/field definition position) (b3/b3Pos {:x 0.0 :y 0.0 :z radius}))
    (ak/= (a/field definition allowFastRotation) true)
    (ak/= (a/field shape density) (/ physics/wheel-mass-kg (* 3.1415927 radius radius width)))
    (ak/= (a/field (a/field shape baseMaterial) friction) physics/tire-friction)
    (ak/= (a/field (a/field shape baseMaterial) restitution) 0.0)
    (let [wheel (b3/b3CreateBody world (ak/& definition))
          collider (b3/b3CreateHullShape wheel (ak/& shape) hull)
          mass (b3/b3Body_GetMassData wheel)]
      (when (ak/== shape-kind 1)
        (b3/b3DestroyShape collider false)
        (let [sphere (b3/b3Sphere {:center {:x 0.0 :y 0.0 :z 0.0} :radius radius})]
          (ak/= :_ (b3/b3CreateSphereShape wheel (ak/& shape) (ak/& sphere))))
        (b3/b3Body_SetMassData wheel mass))
      (when (ak/== shape-kind 2)
        (let [^:var filter (b3/b3Shape_GetFilter collider)]
          (ak/= (a/field filter maskBits) 0)
          (b3/b3Shape_SetFilter collider filter true)))
      (dotimes [_ physics/step-rate]
        (when (ak/== shape-kind 2)
          (flat-tire-contact-probe! wheel radius (* 0.5 width)))
        (physics/step! world))
      (b3/b3Body_ApplyLinearImpulseToCenter wheel
        (b3/b3Vec3 {:x (* (a/field mass mass) initial-speed) :y 0.0 :z 0.0}) true)
      (b3/b3Body_ApplyAngularImpulse wheel
        (b3/b3MulMV (a/field mass inertia)
          (b3/b3Vec3 {:x 0.0 :y (/ initial-speed radius) :z 0.0})) true)
      (dotimes [second 11]
        (when (> second 0)
          (dotimes [_ physics/step-rate]
            (when (ak/== shape-kind 2)
              (flat-tire-contact-probe! wheel radius (* 0.5 width)))
            (physics/step! world)))
        (let [state (physics/body-state wheel)]
          (ak/= (a/index rows second)
            (a/init [(ak/as (ak/floatFromInt second) :f32)
               (a/field state vx) (a/field state vy) (a/field state wy)
               (a/field state z) (a/field state y)
               (a/field state wx) (a/field state wz)] [:array 8 :f32]))))
      rows)))

(a/defn free-rolling-tire-trace [:array 11 [:array 8 :f32]]
  "Original cylinder/sphere control comparison; shape2 is the analytic probe." [[sphere? :bool] [initial-speed :f32]]
  (free-rolling-contact-trace (if sphere? (ak/as 1 :u8) 0) initial-speed))

(a/defn vehicle-contact-probe VehicleResult [[steering :f32] [analytic? :bool]]
  (let [world (physics/create-world -9.81)
        ground (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                                   (b3/b3Vec3 {:x 500.0 :y 500.0 :z 0.5}) 0.0)
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.70}) 0.0)]
    (ak/= :_ ground)
    (ak/defer (physics/destroy-world! world))
    (when analytic? (use-flat-tire-probe! car))
    ;; Settle suspension, then accelerate from rest without writing velocity.
    (dotimes [_ (* 2 physics/step-rate)]
      (when analytic? (apply-flat-tires-probe! car))
      (physics/step! world))
    (dotimes [_ (* 5 physics/step-rate)]
      (physics/drive! car 1.0 0.0 steering)
      (when analytic? (apply-flat-tires-probe! car))
      (physics/step! world))
    (let [before (physics/body-state (a/field car chassis))
          spin (b3/b3WheelJoint_GetSpinSpeed (a/index (a/field car joints) 2))]
      (dotimes [_ (* 3 physics/step-rate)]
        (physics/drive! car 0.0 1.0 0.0)
        (when analytic? (apply-flat-tires-probe! car))
        (physics/step! world))
      (let [after (physics/body-state (a/field car chassis))]
        (VehicleResult {:speed_before_braking
                        (ak/sqrt (+ (* (a/field before vx) (a/field before vx))
                                    (* (a/field before vy) (a/field before vy))))
                        :speed_after_braking
                        (ak/sqrt (+ (* (a/field after vx) (a/field after vx))
                                    (* (a/field after vy) (a/field after vy))))
                        :distance (a/field before x) :height (a/field after z)
                        :wheel_spin spin :yaw (a/field before qz)})))))

(a/defn vehicle-probe VehicleResult [[steering :f32]]
  (vehicle-contact-probe steering false))

(deftest analytic-plane-contact-prototype-test
  ;; These checks concern an isolated plane prototype, not the production
  ;; tire model, complete circuit handling, collisions, or a live AI race.
  (doseq [speed [8.0 32.0 64.0]]
    (let [rows (a/value (free-rolling-contact-trace 2 speed))
          [_ vx vy spin height lateral] (last rows)]
      (is (< (abs (- vx speed)) (* speed 0.01)) (pr-str (last rows)))
      (is (< (abs lateral) 0.05) (pr-str (last rows)))
      (is (< (abs vy) 0.02) (pr-str (last rows)))
      (is (< (abs (- (* spin 0.48) vx)) 0.05) (pr-str (last rows)))
      (is (< 0.46 height 0.49) (pr-str (last rows)))
      (is (every? #(Double/isFinite (double %)) (mapcat identity rows)))))
  (let [rows (a/value (drivetrain-comparison-trace 2 5))
        [_ vx _ _ lateral _ upright] (last rows)
        braking (a/value (vehicle-contact-probe 0.0 true))]
    (is (< 70.0 vx 100.0) (pr-str (last rows)))
    (is (< (abs lateral) 0.05) (pr-str (last rows)))
    (is (> upright 0.99) (pr-str (last rows)))
    (is (> (:speed_before_braking braking) 40.0) (pr-str braking))
    (is (< (:speed_after_braking braking) 1.0) (pr-str braking))))

(deftest high-speed-rigid-contacts-test
  (let [head-on (a/value (impact-probe 0.0))
        offset (a/value (impact-probe 0.7))]
    (is (pos? (:hits head-on)))
    ;; Centre separation <5m is not penetration once the bodies rotate.
    ;; Test actual contact geometry plus non-crossing, not an axis-aligned proxy.
    (is (pos? (:minimum_separation head-on)) "Head-on bodies must not cross")
    (is (< (:maximum_penetration head-on) 0.05)
        "Actual manifold penetration must stay below 5cm at 300km/h each")
    (is (< (abs (:final_speed head-on)) 15.0))
    (is (pos? (:hits offset)))
    (is (> (:max_spin offset) 0.5) "Off-centre impacts must rotate the body")))

(a/defn gear-probe [:array 4 :f32]
  "Settle, engage a physical gear for three seconds, then brake for two.
  No body velocity or transform writes, including when testing reverse." [[gear :i8] [grounded :bool]]
  (let [world (physics/create-world (if grounded -9.81 0.0))
        car (physics/create-vehicle world (b3/b3Pos {:x 0.0 :y 0.0 :z 0.70}) 0.0)]
    (ak/defer (physics/destroy-world! world))
    (when grounded
      (ak/= :_ (physics/create-ground world (b3/b3Pos {:x 0.0 :y 0.0 :z -0.5})
                  (b3/b3Vec3 {:x 100.0 :y 100.0 :z 0.5}) 0.0)))
    (dotimes [_ physics/step-rate]
      (physics/drive-in-gear! car 0.0 1.0 0.0 gear)
      (physics/step! world))
    (let [initial (physics/body-state (a/field car chassis))]
      (dotimes [_ (* 3 physics/step-rate)]
        (physics/drive-in-gear! car 0.3 0.0 0.0 gear)
        (physics/step! world))
      (let [moving (physics/body-state (a/field car chassis))
            spin (b3/b3WheelJoint_GetSpinSpeed (a/index (a/field car joints) 2))]
        (dotimes [_ (* 2 physics/step-rate)]
          (physics/drive-in-gear! car 0.0 1.0 0.0 gear)
          (physics/step! world))
        (a/init [(- (a/field moving x) (a/field initial x)) (a/field moving vx)
           (a/field (physics/body-state (a/field car chassis)) vx) spin] [:array 4 :f32])))))

(deftest physical-reverse-neutral-and-braking-test
  (let [[distance speed stopped spin :as reverse] (a/value (gear-probe -1 true))
        neutral (a/value (gear-probe 0 true))
        airborne (a/value (gear-probe -1 false))]
    (is (< distance -1.0) (pr-str reverse))
    (is (< speed -1.0) (pr-str reverse))
    (is (< spin -1.0) (pr-str reverse))
    (is (< (abs stopped) 0.1) (pr-str reverse))
    (is (< (abs (first neutral)) 0.1) (pr-str neutral))
    (is (< (abs (second neutral)) 0.1) (pr-str neutral))
    (is (< (abs (first airborne)) 0.1) (pr-str airborne))
    (is (> (abs (last airborne)) 1.0) (pr-str airborne))))

(deftest independent-wheels-traction-and-braking-test
  (let [straight (a/value (vehicle-probe 0.0))
        turning (a/value (vehicle-probe 0.10))]
    (is (> (:speed_before_braking straight) 10.0))
    (is (> (:distance straight) 20.0) "Wheel torque must propel the chassis forwards")
    (is (< (:speed_after_braking straight) 1.0))
    (is (< 0.3 (:height straight) 0.9))
    (is (> (abs (:wheel_spin straight)) 10.0))
    (is (> (abs (:yaw turning)) 0.1) "Steering joints must turn the car")
    (is (every? #(Double/isFinite (double %)) (concat (vals straight) (vals turning))))))
