(ns racing-game.physics
  "Metre/kilogram/second rigid-body vehicle dynamics. No track-position snapping.
  Worlds are explicit so isolated physics tests cannot reset the live race."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [racing-game.vehicle-spec :as vehicle-spec]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]))

(a/defstruct Vehicle {:layout :extern}
  [[:chassis b3/b3BodyId]
   [:wheels [:array 4 b3/b3BodyId]]
   [:joints [:array 4 b3/b3JointId]]])

(a/defstruct BodyState {:layout :extern}
  [[:x :f32] [:y :f32] [:z :f32]
   [:vx :f32] [:vy :f32] [:vz :f32]
   [:qx :f32] [:qy :f32] [:qz :f32] [:qw :f32]
   [:wx :f32] [:wy :f32] [:wz :f32]])

(a/defconst solid-category :u64 1)

(a/defconst tire-surface-category :u64 2)

(a/defconst tire-category :u64 4)

(a/defconst max-world-tires :usize 512)

(a/defstruct TireContact
  [[:body b3/b3BodyId] [:radius :f32] [:half-width :f32] [:enabled :bool]
   [:wear_loss :f64]])

(a/defstruct WorldState
  "Owned by one physics world; never shared between races or test fixtures."
  [[:count :usize] [:tires [:array max-world-tires TireContact]]])

(a/defn create-world b3/b3WorldId [[gravity :f32]]
  (let [^:var definition (b3/b3DefaultWorldDef)
        state (catch ((a/field heap/page_allocator create) WorldState)
                (debug/panic "Unable to allocate the physics world's tire registry" []))]
    (ak/= (a/deref state) (mem/zeroes (a/type WorldState)))
    (ak/= (a/field definition gravity) (b3/b3Vec3 {:x 0.0 :y 0.0 :z gravity}))
    (ak/= (a/field definition maximumLinearSpeed) 130.0)
    (ak/= (a/field definition enableContinuous) true)
    (ak/= (a/field definition contactHertz) 120.0)
    (ak/= (a/field definition workerCount) 1)
    (ak/= (a/field definition userData) state)
    (b3/b3CreateWorld (ak/& definition))))

(a/defn destroy-world! :void [[world b3/b3WorldId]]
  (let [state (b3/b3World_GetUserData world)]
    (b3/b3World_SetUserData world ak/null)
    (b3/b3DestroyWorld world)
    (when (ak/!= state ak/null)
      ((a/field heap/page_allocator destroy) (a/cast state [:* WorldState])))))

(a/defn register-tire! :void
  [[world b3/b3WorldId] [body b3/b3BodyId] [radius :f32] [half-width :f32]]
  (let [state (a/cast (b3/b3World_GetUserData world) [:* WorldState])
        ^:var slot (ak/usize (a/field state count))]
    (dotimes [i (a/field state count)]
      (when (ak/! (b3/b3Body_IsValid (a/field (a/index (a/field state tires) i) body)))
        (ak/= slot i)
        (ak/break)))
    (when (>= slot max-world-tires)
      (debug/panic "Physics world tire registry exhausted (maximum {d} tires)" [max-world-tires]))
    (when (ak/== slot (a/field state count))
      (ak/+= (a/field state count) 1))
    (ak/= (a/index (a/field state tires) slot)
      (TireContact {:body body :radius radius :half-width half-width :enabled true :wear_loss 0.0}))))

(a/defn set-tire-contact-enabled! :void
  "Enable/disable the analytic road contact for one registered tire. This does
  not change rigid collision filters. Useful for contact-model comparisons." [[body b3/b3BodyId] [enabled :bool]]
  (let [raw (b3/b3World_GetUserData (b3/b3Body_GetWorld body))]
    (when (ak/!= raw ak/null)
      (let [state (a/cast raw [:* WorldState])]
        (dotimes [i (a/field state count)]
          (let [tire (ak/& (a/index (a/field state tires) i))
                candidate (a/field tire body)]
            (when (and (ak/== (a/field candidate index1) (a/field body index1))
                       (ak/== (a/field candidate generation) (a/field body generation)))
              (ak/= (a/field tire enabled) enabled)
              (ak/return))))))))

(a/defconst step-rate :usize 3840)

(a/defconst fixed-step :f32 (/ 1.0 (ak/as (ak/floatFromInt step-rate) :f32)))

(a/defconst chassis-mass-kg :f32 700.0)

(a/defconst wheel-mass-kg :f32 23.0)

;; Simplified open-wheel setup, not measured data for a particular real car.
;; Asphalt coefficient 1.0 combines with 4.0 to give effective Coulomb mu 2.0.
(a/defconst tire-friction :f32 4.0)

(a/defconst engine-power-watts :f32 750000.0)

(a/defconst rear-wheel-torque-nm :f32 2200.0)

;; Forces in newtons are these coefficients times speed squared (m²/s²).
(a/defconst downforce-coefficient :f32 8.0)

(a/defconst drag-coefficient :f32 0.75)

(a/defn body-state BodyState [[body b3/b3BodyId]]
  (let [p (b3/b3Body_GetPosition body) v (b3/b3Body_GetLinearVelocity body)
        q (b3/b3Body_GetRotation body) w (b3/b3Body_GetAngularVelocity body)]
    (BodyState {:x (a/field p x) :y (a/field p y) :z (a/field p z)
                :vx (a/field v x) :vy (a/field v y) :vz (a/field v z)
                :qx (a/field (a/field q v) x) :qy (a/field (a/field q v) y)
                :qz (a/field (a/field q v) z) :qw (a/field q s)
                :wx (a/field w x) :wy (a/field w y) :wz (a/field w z)})))

(a/defn create-box b3/b3BodyId
  "Rigid collider; mass=0 creates a static body. Also useful for barriers/tests."
  [[world b3/b3WorldId] [position b3/b3Pos] [half-size b3/b3Vec3]
   [heading :f32] [mass :f32]]
  (let [^:var definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)
        hull (b3/b3MakeBoxHull (a/field half-size x) (a/field half-size y)
                              (a/field half-size z))]
    (ak/= (a/field definition type) (if (> mass 0.0) b3/b3_dynamicBody b3/b3_staticBody))
    (ak/= (a/field definition position) position)
    (ak/= (a/field definition rotation)
          (b3/b3MakeQuatFromAxisAngle (b3/b3Vec3 {:x 0.0 :y 0.0 :z 1.0}) heading))
    (ak/= (a/field shape density)
          (/ mass (* 8.0 (a/field half-size x) (a/field half-size y) (a/field half-size z))))
    (ak/= (a/field (a/field shape baseMaterial) friction) 0.8)
    (ak/= (a/field (a/field shape baseMaterial) restitution) 0.05)
    (ak/= (a/field shape enableHitEvents) true)
    (ak/= (a/field (a/field shape filter) categoryBits) solid-category)
    (let [body (b3/b3CreateBody world (ak/& definition))]
      (ak/= :_ (b3/b3CreateHullShape body (ak/& shape) (ak/& (a/field hull base))))
      body)))

(a/defn mark-tire-surface! :void
  "Mark an explicit STATIC driving surface. Barriers/props stay solid-category;
  they retain ordinary rigid tire contacts rather than analytic road forces." [[body b3/b3BodyId]]
  (when (ak/!= (b3/b3Body_GetType body) b3/b3_staticBody)
    (debug/panic "Analytic tire surfaces must be static bodies" []))
  (let [^:var shapes (mem/zeroes (a/type [:array 32 b3/b3ShapeId]))
        count (b3/b3Body_GetShapes body (ak/& shapes) 32)]
    (when (> (b3/b3Body_GetShapeCount body) 32)
      (debug/panic "mark-tire-surface! supports at most 32 shapes per body" []))
    (dotimes [i count]
      (let [shape (a/index shapes i)
            ^:var filter (b3/b3Shape_GetFilter shape)]
        (ak/= (a/field filter categoryBits) tire-surface-category)
        (b3/b3Shape_SetFilter shape filter true)))))

(a/defn create-ground b3/b3BodyId
  "Static box driving surface for fixtures/platforms, explicitly not a barrier." [[world b3/b3WorldId] [position b3/b3Pos]
                  [half-size b3/b3Vec3] [heading :f32]]
  (let [body (create-box world position half-size heading 0.0)]
    (mark-tire-surface! body)
    body))

(a/defn create-vehicle Vehicle
  "Independent chassis and four rotating tire bodies with suspension/steering
  joints. Origin is chassis centre, in metres. No upright constraint or angular
  motion locks: collisions can spin, pitch and roll the car." [[world b3/b3WorldId] [position b3/b3Pos] [heading :f32]]
  (let [chassis (create-box world position (b3/b3Vec3 {:x 2.5 :y 0.75 :z 0.20}) heading chassis-mass-kg)
        rotation (b3/b3Body_GetRotation chassis)
        ^:var vehicle (mem/zeroes (a/type Vehicle))]
    (ak/= (a/field vehicle chassis) chassis)
    (dotimes [i 4]
      (let [front (< i 2)
            dimensions (a/index vehicle-spec/wheel-geometry i)
            radius (a/index dimensions 3)
            width (a/index dimensions 4)
            ;; Upstream cylinder starts at yOffset; centre it on the axle.
            tire (b3/b3CreateCylinder width radius (* -0.5 width) 32)
            offset (b3/b3Vec3 {:x (a/index dimensions 0)
                              :y (a/index dimensions 1)
                              :z (- (a/index dimensions 2) vehicle-spec/chassis-origin-z)})
            translated (b3/b3RotateVector rotation offset)
            ^:var body-definition (b3/b3DefaultBodyDef)
            ^:var shape (b3/b3DefaultShapeDef)
            ^:var joint (b3/b3DefaultWheelJointDef)]
        (ak/defer (b3/b3DestroyHull tire))
        (ak/= (a/field body-definition type) b3/b3_dynamicBody)
        (ak/= (a/field body-definition position)
              (b3/b3Pos {:x (+ (a/field position x) (a/field translated x))
                         :y (+ (a/field position y) (a/field translated y))
                         :z (+ (a/field position z) (a/field translated z))}))
        (ak/= (a/field body-definition rotation) rotation)
        (ak/= (a/field body-definition allowFastRotation) true)
        (ak/= (a/field shape density) (/ wheel-mass-kg (* 3.1415927 radius radius width)))
        (ak/= (a/field (a/field shape baseMaterial) friction) tire-friction)
        (ak/= (a/field (a/field shape baseMaterial) restitution) 0.0)
        (ak/= (a/field shape enableHitEvents) true)
        (ak/= (a/field (a/field shape filter) categoryBits) tire-category)
        (ak/= (a/field (a/field shape filter) maskBits) (ak/bit-not tire-surface-category))
        (let [wheel (b3/b3CreateBody world (ak/& body-definition))]
          (ak/= :_ (b3/b3CreateHullShape wheel (ak/& shape) tire))
          (ak/= (a/index (a/field vehicle wheels) i) wheel)
          (ak/= (a/field (a/field joint base) bodyIdA) chassis)
          (ak/= (a/field (a/field joint base) bodyIdB) wheel)
          (ak/= (a/field (a/field (a/field joint base) localFrameA) p) offset)
          ;; Box3D: A.X is suspension, B.Z is spin, and A.Z/B.Z must
          ;; coincide at zero steering. Both bodies use the same rotation.
          ;; This cyclic frame maps X->Z, Y->X, Z->Y for our Z-up vehicle.
          (ak/= (a/field (a/field (a/field joint base) localFrameA) q)
                (b3/b3Quat {:v {:x -0.5 :y -0.5 :z -0.5} :s 0.5}))
          (ak/= (a/field (a/field (a/field joint base) localFrameB) q)
                (b3/b3Quat {:v {:x -0.5 :y -0.5 :z -0.5} :s 0.5}))
          (ak/= (a/field joint enableSuspensionSpring) true)
          (ak/= (a/field joint suspensionHertz) 9.0)
          (ak/= (a/field joint suspensionDampingRatio) 1.0)
          (ak/= (a/field joint enableSuspensionLimit) true)
          (ak/= (a/field joint lowerSuspensionLimit) -0.12)
          (ak/= (a/field joint upperSuspensionLimit) 0.12)
          (ak/= (a/field joint enableSteering) front)
          (ak/= (a/field joint steeringHertz) 30.0)
          (ak/= (a/field joint steeringDampingRatio) 1.0)
          (ak/= (a/field joint maxSteeringTorque) 6000.0)
          (ak/= (a/field joint enableSteeringLimit) true)
          (ak/= (a/field joint lowerSteeringLimit) (if front -0.45 0.0))
          (ak/= (a/field joint upperSteeringLimit) (if front 0.45 0.0))
          (ak/= (a/field joint enableSpinMotor) true)
          (ak/= (a/field joint maxSpinTorque) 0.0)
          (ak/= (a/index (a/field vehicle joints) i)
                (b3/b3CreateWheelJoint world (ak/& joint)))
          (register-tire! world wheel radius (* 0.5 width)))))
    vehicle))

(a/defn drive-in-gear! :void
  "Driver intent produces wheel torque, never an imposed chassis velocity.
  Inputs: throttle/brake 0..1, steering radians (-0.45..0.45), gear -1 reverse,
  0 neutral or 1 forward. Braking opposes rolling in either direction." [[vehicle Vehicle] [throttle :f32] [brake :f32] [steering :f32] [gear :i8]]
  (let [gas (ak/max 0.0 (ak/min 1.0 throttle))
        braking (ak/max 0.0 (ak/min 1.0 brake))
        direction (ak/f32 (cond (< gear 0) -1.0 (> gear 0) 1.0 :else 0.0))
        body (a/field vehicle chassis)
        velocity (b3/b3Body_GetLinearVelocity body)
        forward (b3/b3RotateVector (b3/b3Body_GetRotation body)
                                   (b3/b3Vec3 {:x 1.0 :y 0.0 :z 0.0}))
        forward-speed (+ (* (a/field velocity x) (a/field forward x))
                         (* (a/field velocity y) (a/field forward y))
                         (* (a/field velocity z) (a/field forward z)))]
    (dotimes [i 4]
      (let [joint (a/index (a/field vehicle joints) i)
            radius (a/index (a/index vehicle-spec/wheel-geometry i) 3)
            wheel-velocity (b3/b3Body_GetLinearVelocity (a/index (a/field vehicle wheels) i))
            wheel-speed (+ (* (a/field wheel-velocity x) (a/field forward x))
                           (* (a/field wheel-velocity y) (a/field forward y))
                           (* (a/field wheel-velocity z) (a/field forward z)))
            angle (ak/max -0.45 (ak/min 0.45 steering))
            wheelbase (- (a/index (a/index vehicle-spec/wheel-geometry 0) 0)
                         (a/index (a/index vehicle-spec/wheel-geometry 2) 0))
            ;; Ackermann geometry: inside and outside front wheels describe
            ;; concentric turns rather than scrubbing against one another.
            wheel-angle (math/atan2 (* wheelbase (math/sin angle))
                           (- (* wheelbase (math/cos angle))
                              (* (a/index (a/index vehicle-spec/wheel-geometry i) 1)
                                 (math/sin angle))))
            ;; Limit wheel slip, not chassis velocity; the actual radius matters.
            ;; Each driven wheel uses its own contact travel speed (differential).
            spin-target (* direction
                          (ak/min (if (< gear 0) (ak/as 16.0 :f32) 260.0)
                            (+ (ak/max 0.0 (* direction (/ wheel-speed radius))) (* gas 8.0))))
            ;; Basic ABS: do not ask all four wheels to lock instantly at speed.
            ;; Braking torque still acts through tire contacts; preserve rolling
            ;; while shedding speed so the front wheels can continue steering.
            brake-target (* (if (< wheel-speed 0.0) (ak/as -1.0 :f32) 1.0)
                            (ak/max 0.0 (- (ak/abs (/ wheel-speed radius)) (* braking 8.0))))
            ;; Rear-wheel propulsion has both a torque and shaft-power bound.
            engine-torque (ak/min rear-wheel-torque-nm (/ engine-power-watts
                              (* 2.0 (ak/max 10.0 (ak/abs (b3/b3WheelJoint_GetSpinSpeed joint))))))
            ;; Front-biased braking accounts for longitudinal load transfer.
            torque (if (> braking 0.0) (* (if (< i 2) (ak/as 3200.0 :f32) 1600.0) braking)
                       (if (and (>= i 2) (ak/!= gear 0)) (* engine-torque gas) (ak/as 0.0 :f32)))]
        (b3/b3WheelJoint_SetTargetSteeringAngle joint (if (< i 2) (ak/max -0.45 (ak/min 0.45 wheel-angle)) 0.0))
        (b3/b3WheelJoint_SetMaxSpinTorque joint torque)
        (b3/b3WheelJoint_SetSpinMotorSpeed joint (if (> braking 0.0) brake-target spin-target))))
    (let [v velocity
          speed (ak/sqrt (+ (* (a/field v x) (a/field v x))
                             (* (a/field v y) (a/field v y))
                             (* (a/field v z) (a/field v z))))
          ;; A wing force follows chassis orientation, not a fake upright lock.
          ;; A flipped car is not snapped down. This is a simplified aero model,
          ;; not a CFD or tire-temperature model.
          wing (b3/b3RotateVector (b3/b3Body_GetRotation body)
                 (b3/b3Vec3 {:x 0.0 :y 0.0
                             :z (* (- downforce-coefficient) forward-speed forward-speed)}))]
      ;; Quadratic drag plus downforce. Both are forces integrated by Box3D.
      (b3/b3Body_ApplyForceToCenter body
        (b3/b3Vec3 {:x (+ (* (- drag-coefficient) speed (a/field v x)) (a/field wing x))
                    :y (+ (* (- drag-coefficient) speed (a/field v y)) (a/field wing y))
                    :z (+ (* (- drag-coefficient) speed (a/field v z)) (a/field wing z))}) true))))

(a/defn drive! :void
  "Forward-gear pedal control. Explicit reverse/neutral use drive-in-gear!." [[vehicle Vehicle] [throttle :f32] [brake :f32] [steering :f32]]
  (drive-in-gear! vehicle throttle brake steering 1))

(a/defn tread-wear-step :f64
  "Gameplay wear calibrated from contact work, not elapsed waiting or AI intent.
  Slip dissipation and load-weighted rolling travel consume tread. The 2MJ
  budget and rolling coefficient are tuning parameters, not measured F1 data.
  Tiny solver velocities below 0.02m/s are treated as resting contact noise." [[normal :f32] [friction :f32] [slip :f32] [travel-speed :f32] [seconds :f32]]
  (when (or (<= normal 0.0) (<= seconds 0.0)) (ak/return 0.0))
  (let [rolling (ak/max 0.0 (- travel-speed 0.02))
        sliding (ak/max 0.0 (- slip 0.02))
        watts (+ (* (ak/max 0.0 friction) sliding) (* 0.01 normal rolling))]
    (/ (* (ak/as (ak/floatCast watts) :f64) (ak/as (ak/floatCast seconds) :f64)) 2000000.0)))

(a/defn tire-plane-step! :f64
  "Finite-width circular tread against a static local surface plane. Normal
  compliance and Coulomb-limited slip forces act at contact points; Box3D
  integrates wheel spin, suspension reactions and chassis movement. Near a
  side-on tire, four endcap samples support the disk instead of losing ground.
  This is a simplified tire model, not a tire-temperature or pneumatic model." [[tire TireContact] [surface-normal b3/b3Vec3]
            [surface-point b3/b3Pos] [surface-friction :f32]]
  (let [wheel (a/field tire body)
        radius (a/field tire radius)
        half-width (a/field tire half-width)
        position (b3/b3Body_GetPosition wheel)
        rotation (b3/b3Body_GetRotation wheel)
        axis (b3/b3RotateVector rotation (b3/b3Vec3 {:x 0.0 :y 1.0 :z 0.0}))
        vertical (b3/b3Dot axis surface-normal)
        radial-length (ak/sqrt (ak/max 0.0 (- 1.0 (* vertical vertical))))
        tread? (> radial-length 0.05)
        count (if tread? (ak/as 2 :usize) 4)
        shares (ak/as (ak/floatFromInt count) :f32)
        mass (b3/b3Body_GetMassData wheel)
        inverse-inertia (b3/b3Body_GetWorldInverseRotationalInertia wheel)
        centre-velocity (b3/b3Body_GetLinearVelocity wheel)
        travel (b3/b3Sub centre-velocity
                 (b3/b3MulSV (b3/b3Dot centre-velocity surface-normal) surface-normal))
        travel-speed (ak/sqrt (b3/b3Dot travel travel))
        ^:var wear (ak/f64 0.0)]
    (dotimes [edge count]
      (let [side (* half-width (if (ak/== edge 0) (ak/as -1.0 :f32) 1.0))
            r (if tread?
                (b3/b3Add
                  (b3/b3MulSV (/ radius radial-length)
                    (b3/b3Sub (b3/b3MulSV vertical axis) surface-normal))
                  (b3/b3MulSV side axis))
                (let [angle (* (ak/as (ak/floatFromInt edge) :f32) 1.57079632679)
                      cap (* half-width (if (> vertical 0.0) (ak/as -1.0 :f32) 1.0))]
                  (b3/b3RotateVector rotation
                    (b3/b3Vec3 {:x (* radius (ak/cos angle)) :y cap :z (* radius (ak/sin angle))}))))
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
            edge-mass (/ (a/field mass mass) shares)
            normal (if (> penetration 0.0)
                     (ak/max 0.0 (- (* edge-mass frequency frequency penetration)
                                     (* 2.0 edge-mass frequency normal-speed)))
                     (ak/as 0.0 :f32))
            slip (ak/sqrt (b3/b3Dot tangent tangent))
            direction (b3/b3MulSV (/ 1.0 (ak/max slip 0.000001)) tangent)
            arm (b3/b3Cross r direction)
            inverse-mass (+ (/ 1.0 (a/field mass mass))
                            (b3/b3Dot arm (b3/b3MulMV inverse-inertia arm)))
            friction (ak/min (* (ak/sqrt (* tire-friction surface-friction)) normal)
                         (/ slip (* shares fixed-step inverse-mass)))]
        (b3/b3Body_ApplyForce wheel
          (b3/b3Sub (b3/b3MulSV normal surface-normal)
                    (b3/b3MulSV friction direction)) point true)
        (ak/+= wear (tread-wear-step normal friction slip travel-speed fixed-step))))
    wear))

(a/defn apply-tire-plane! :void
  "Explicit contact experiment; production contacts also retain tread loss." [[tire TireContact] [surface-normal b3/b3Vec3]
            [surface-point b3/b3Pos] [surface-friction :f32]]
  (ak/= :_ (tire-plane-step! tire surface-normal surface-point surface-friction)))

(a/defn take-vehicle-tread-loss! :f32
  "Consume measured contact work once for this vehicle. A pit call does not
  stop accumulation; an airborne wheel has no supporting contact work." [[vehicle Vehicle]]
  (let [world (b3/b3Body_GetWorld (a/field vehicle chassis))
        raw (b3/b3World_GetUserData world)
        ^:var loss (ak/f64 0.0)]
    (when (ak/!= raw ak/null)
      (let [state (a/cast raw [:* WorldState])]
        (dotimes [i (a/field state count)]
          (let [tire (ak/& (a/index (a/field state tires) i))
                body (a/field tire body)]
            (dotimes [wheel-index 4]
              (let [wheel (a/index (a/field vehicle wheels) wheel-index)]
                (when (and (ak/== (a/field body index1) (a/field wheel index1))
                           (ak/== (a/field body generation) (a/field wheel generation)))
                  (ak/+= loss (a/field tire wear_loss))
                  (ak/= (a/field tire wear_loss) 0.0))))))))
    (ak/floatCast (/ loss 4.0))))

(a/defn apply-tire-contacts! :void
  "Apply each world's tire contacts independently of driver input. Ground is
  queried from Box3D, never inferred from circuit progress. Unmarked props and
  barriers retain rigid contacts. Destroyed body generations are not reused." [[world b3/b3WorldId]]
  (let [raw (b3/b3World_GetUserData world)]
    ;; A pre-registry world keeps its existing rigid wheel contacts until it is
    ;; explicitly recreated. Never reinterpret an absent registry as memory.
    (when (ak/!= raw ak/null)
      (let [state (a/cast raw [:* WorldState])
            ^:var query (b3/b3DefaultQueryFilter)]
        (ak/= (a/field query maskBits) tire-surface-category)
        (dotimes [i (a/field state count)]
          (let [tire (a/index (a/field state tires) i)
                wheel (a/field tire body)]
            (when (and (a/field tire enabled) (b3/b3Body_IsValid wheel))
              (let [p (b3/b3Body_GetPosition wheel)
                    reach (+ (a/field tire radius) (a/field tire half-width) 0.05)
                    hit (b3/b3World_CastRayClosest world
                          (b3/b3Pos {:x (a/field p x) :y (a/field p y) :z (+ (a/field p z) reach)})
                          (b3/b3Vec3 {:x 0.0 :y 0.0 :z (* -2.0 reach)}) query)]
                (when (a/field hit hit)
                  (let [shape (a/field hit shapeId)
                        filter (b3/b3Shape_GetFilter shape)]
                    ;; Box3D defaults categories to ALL bits. Only an explicit
                    ;; surface marker authorizes analytic contact replacement.
                    (when (and (ak/== (a/field filter categoryBits) tire-surface-category)
                               (ak/== (b3/b3Body_GetType (b3/b3Shape_GetBody shape)) b3/b3_staticBody))
                      (let [^:var material (b3/b3Shape_GetSurfaceMaterial shape)]
                        (when (and (ak/== (b3/b3Shape_GetType shape) b3/b3_meshShape)
                                   (>= (a/field hit triangleIndex) 0))
                          (let [mesh (b3/b3Shape_GetMesh shape)
                                indices (b3/b3GetMeshMaterialIndices (a/field mesh data))]
                            (when (ak/!= indices ak/null)
                              (ak/= material (b3/b3Shape_GetMeshSurfaceMaterial shape
                                (a/index indices (ak/as (ak/intCast (a/field hit triangleIndex)) :usize)))))))
                        (ak/+= (a/field (a/index (a/field state tires) i) wear_loss)
                          (tire-plane-step! tire (a/field hit normal) (a/field hit point)
                            (a/field material friction)))))))))))))))

(a/defn step! :void [[world b3/b3WorldId]]
  ;; Contacts run even during settling, neutral coasting, or retirement.
  (apply-tire-contacts! world)
  (b3/b3World_Step world fixed-step 2))
