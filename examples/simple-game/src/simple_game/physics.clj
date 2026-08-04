(ns simple-game.physics
  "Hot-reloadable Box3D sphere particles shared by every target."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings]
            [simple-game.bindings.box3d :as box3d]))

(az/defconst particle-capacity :usize 32)

(az/defconst particles-per-click :usize 6)

(az/defn ParticleType
  "Build particle storage around the selected native physics body handle."
  {:attrs #{:public :implicit-return}}
  :-
  :type
  [[BodyId {:zig/prefix "comptime"} :type]]
  (az/container
   {:kind :struct :layout :extern}
   (az/field-decl body BodyId)
   (az/field-decl age :f32)
   (az/field-decl radius :f32)
   (az/field-decl tint :u32)
   (az/field-decl active :bool)))

(az/defconst Particle
  "One pooled particle backed by a real Box3D body."
  {:attrs #{:public}}
  (ParticleType box3d/b3BodyId))

(az/defstruct ParticleView
  "Inspectable renderer view of one 3D particle."
  {:layout :extern}
  [[:active :bool]
   [:x :f32]
   [:y :f32]
   [:z :f32]
   [:radius :f32]
   [:age :f32]
   [:tint :u32]])

(az/defstruct PhysicsSnapshot
  "Small nREPL view of Box3D and the bounded particle pool."
  {:layout :extern}
  [[:initialized :bool]
   [:active_particles :u32]
   [:capacity :u32]
   [:box3d_bytes :i32]])

(az/defvar initialized false)

(az/defvar world box3d/b3WorldId
  (std-mem/zeroes (az/type box3d/b3WorldId)))

(az/defvar particles [:array 32 Particle]
  (std-mem/zeroes (az/type [:array 32 Particle])))

(az/defvar next-slot :usize 0)

(az/defn initialize!
  "Create one Box3D world; repeated calls preserve live bodies."
  :-
  :bool
  []
  (when (ak/! initialized)
    (let [^{:var true}
          definition (box3d/b3DefaultWorldDef)]
      (set! (az/field definition gravity)
            (box3d/b3Vec3 {:x 0.0 :y 7.5 :z 0.0}))
      (set! (az/field definition workerCount) 1)
      (set! world (box3d/b3CreateWorld (ak/& definition)))
      (set! particles (std-mem/zeroes (az/type [:array 32 Particle])))
      (set! next-slot 0)
      (set! initialized true)))
  initialized)

(az/defn destroy-slot!
  :-
  :void
  [[slot :usize]]
  (let [particle (ak/& (az/index particles slot))]
    (when (az/field (az/deref particle) active)
      (box3d/b3DestroyBody (az/field (az/deref particle) body))
      (set! (az/field (az/deref particle) active) false))))

(az/defn spawn-one!
  "Create one physical sphere in a deterministic radial burst."
  :-
  :void
  [[ordinal :usize]
   [tint :u32]]
  (set! _ (initialize!))
  (let [slot next-slot
        ordinal-f (ak/as :f32 (ak/floatFromInt ordinal))
        angle (* ordinal-f 1.0471976)
        speed (+ 3.8 (* ordinal-f 0.18))
        radius (+ 0.13 (* 0.015 (ak/as :f32 (ak/floatFromInt (mod ordinal 3)))))
        ^{:var true}
        body-definition (box3d/b3DefaultBodyDef)
        ^{:var true}
        shape-definition (box3d/b3DefaultShapeDef)
        sphere (box3d/b3Sphere
                {:center (box3d/b3Vec3 {:x 0.0 :y 0.0 :z 0.0})
                 :radius radius})]
    (destroy-slot! slot)
    (set! (az/field body-definition type) box3d/b3_dynamicBody)
    (set! (az/field body-definition position)
          (box3d/b3Pos {:x 0.0 :y 0.0 :z 0.0}))
    (set! (az/field body-definition linearVelocity)
          (box3d/b3Vec3
           {:x (* (std-math/cos angle) speed)
            :y (- (* (std-math/sin angle) speed) 4.5)
            :z (- (* ordinal-f 0.72) 1.8)}))
    (set! (az/field body-definition angularVelocity)
          (box3d/b3Vec3 {:x (* 1.4 ordinal-f)
                         :y 2.0
                         :z (- 3.0 ordinal-f)}))
    (set! (az/field body-definition linearDamping) 0.08)
    (set! (az/field body-definition angularDamping) 0.12)
    (set! (az/field body-definition enableSleep) false)
    (set! (az/field shape-definition density) 0.7)
    (set! (az/field (az/field shape-definition baseMaterial) friction) 0.25)
    (set! (az/field (az/field shape-definition baseMaterial) restitution) 0.55)
    (let [body (box3d/b3CreateBody world (ak/& body-definition))]
      (set! _ (box3d/b3CreateSphereShape
               body (ak/& shape-definition) (ak/& sphere)))
      (set! (az/index particles slot)
            (Particle {:body body
                       :age 0.0
                       :radius radius
                       :tint tint
                       :active true})))
    (set! next-slot (mod (+ next-slot 1) particle-capacity))))

(az/defn emit!
  "Emit a bounded six-sphere Box3D burst from the main circle."
  :-
  :void
  [[tint :u32]]
  (dotimes [ordinal particles-per-click]
    (spawn-one! ordinal tint)))

(az/defn step!
  "Advance Box3D and retire old particle bodies."
  :-
  :void
  [[delta-seconds :f32]]
  (when initialized
    (let [step (if (> delta-seconds 0.0) delta-seconds 0.008333333)]
      (box3d/b3World_Step world step 4)
      (dotimes [slot particle-capacity]
        (let [particle (ak/& (az/index particles slot))]
          (when (az/field (az/deref particle) active)
            (set! (az/field (az/deref particle) age)
                  (+ (az/field (az/deref particle) age) step))
            (when (> (az/field (az/deref particle) age) 2.2)
              (destroy-slot! slot))))))))

(az/defn particle-view
  "Return one exact Box3D position for rendering or nREPL inspection."
  :-
  ParticleView
  [[slot :usize]]
  (if (or (>= slot particle-capacity)
          (ak/! (az/field (az/index particles slot) active)))
    (ParticleView {:active false
                   :x 0.0 :y 0.0 :z 0.0
                   :radius 0.0 :age 0.0 :tint 0})
    (let [particle (ak/& (az/index particles slot))
          position (box3d/b3Body_GetPosition
                    (az/field (az/deref particle) body))]
      (ParticleView
       {:active true
        :x (az/field position x)
        :y (az/field position y)
        :z (az/field position z)
        :radius (az/field (az/deref particle) radius)
        :age (az/field (az/deref particle) age)
        :tint (az/field (az/deref particle) tint)}))))

(az/defn active-count
  :-
  :u32
  []
  (let [^{:var true :zig/type :u32} count 0]
    (dotimes [slot particle-capacity]
      (when (az/field (az/index particles slot) active)
        (set! count (+ count 1))))
    count))

(az/defn snapshot
  "Inspect the live Box3D allocation and pool occupancy."
  :-
  PhysicsSnapshot
  []
  (PhysicsSnapshot
   {:initialized initialized
    :active_particles (active-count)
    :capacity (ak/intCast particle-capacity)
    :box3d_bytes (box3d/b3GetByteCount)}))

(az/defn shutdown!
  "Destroy every Box3D body and the world."
  :-
  :void
  []
  (when initialized
    (box3d/b3DestroyWorld world)
    (set! particles (std-mem/zeroes (az/type [:array 32 Particle])))
    (set! world (std-mem/zeroes (az/type box3d/b3WorldId)))
    (set! next-slot 0)
    (set! initialized false)))
