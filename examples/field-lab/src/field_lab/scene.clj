(ns field-lab.scene
  "Flecs owns graph nodes, inputs, and authoritative body state."
  (:refer-clojure :exclude [count reset!])
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.flecs :as ecs]
            [field-lab.physics :as p]
            [field-lab.contacts :as contacts]
            [field-lab.soft-body :as soft]
            [field-lab.ball-fem :as fem]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.experiments :as experiments]))

(az/defvar world [:optional [:* ecs/ecs_world_t]] null)

(az/defvar bodies [:array 3 :u64] [0 0 0])

(az/defvar body-count :u32 1)

(az/defvar source :u64 0)

(az/defvar solver :u64 0)

(az/defvar output :u64 0)

(az/defvar config-id :u64 0)

(az/defvar state-id :u64 0)

(az/defvar soft-id :u64 0)

(az/defstruct BallMaterial {:layout :extern}
  [[:model :u32] [:young :f64] [:poisson :f64]])

(az/defvar material-id :u64 0)

(az/defvar deformable :bool false)

(az/defvar continuum :bool false)

(az/defvar requested-continuum :bool false)

(az/defvar solver-failed :bool false)

(az/defvar minimum-jacobian :f64 1.0)

(az/defvar stiffness :f64 10000.0)

(az/defvar depends-on :u64 0)

(az/defconst capacity :usize 14401)

(az/defvar history [:array capacity contacts/Sample] ak/undefined)

(az/defvar soft-history [:array capacity soft/Sample] ak/undefined)

(az/defvar count :u32 0)

(az/defvar cursor :u32 0)

(az/defvar revision :u32 0)

(az/defstruct MeshCacheRef {:layout :extern}
  [[:value [:optional [:* cache/Cache]]]])

(az/defvar mesh-cache-id :u64 0)

(az/defstruct MeshGroupRef {:layout :extern}
  [[:value [:optional [:* group/Group]]]])

(az/defvar mesh-group-id :u64 0)

(az/defn single-mesh-cache
  :- [:optional [:* cache/Cache]]
  []
  (when (or (ak/== world null) (ak/== mesh-cache-id 0)) (ak/return null))
  (let [component (ecs/ecs_get_id world output mesh-cache-id)]
    (when (ak/== component null) (ak/return null))
    (az/field (az/deref (az/cast component [:*const MeshCacheRef])) value)))

(az/defn mesh-group
  :- [:optional [:* group/Group]]
  []
  (when (or (ak/== world null) (ak/== mesh-group-id 0)) (ak/return null))
  (let [component (ecs/ecs_get_id world output mesh-group-id)]
    (when (ak/== component null) (ak/return null))
    (az/field (az/deref (az/cast component [:*const MeshGroupRef])) value)))

(az/defn mesh-cache-at
  :- [:optional [:* cache/Cache]]
  [[body :usize]]
  (let [owned (mesh-group)]
    (when (ak/!= owned null)
      (when (>= body (az/field (az/unwrap owned) count)) (ak/return null))
      (ak/return (group/item (az/unwrap owned) body))))
  (if (ak/== body 0) (single-mesh-cache) null))

(az/defn mesh-cache
  :- [:optional [:* cache/Cache]]
  []
  (mesh-cache-at 0))

(az/defn clear-mesh-cache!
  :- :void
  []
  (let [owned (single-mesh-cache)
        collection (mesh-group)]
    (when (ak/!= owned null)
      (ecs/ecs_remove_id world output mesh-cache-id)
      (cache/destroy! (az/unwrap owned)))
    (when (ak/!= collection null)
      (ecs/ecs_remove_id world output mesh-group-id)
      (group/destroy! (az/unwrap collection)))))

(az/defconst dt :f64 0.004166666666666667)

(az/defn entity!
  :- :u64
  [[name [:pointer {:size :c :const? true} :u8]]]
  (let [description (ecs/ecs_entity_desc_t {:name name})]
    (ecs/ecs_entity_init world (ak/& description))))

(az/defn component!
  :- :u64
  [[name [:pointer {:size :c :const? true} :u8]] [size :usize] [alignment :usize]]
  (let [info (ecs/ecs_type_info_t {:size (ak/intCast size) :alignment (ak/intCast alignment)})
        description (ecs/ecs_component_desc_t {:entity (entity! name) :type info})]
    (ecs/ecs_component_init world (ak/& description))))

(az/defn config
  :- p/Config
  []
  (az/deref (az/cast (ecs/ecs_get_id world source config-id) [:*const p/Config])))

(az/defn body-state
  :- p/State
  [[index :u32]]
  (az/deref (az/cast (ecs/ecs_get_id world (az/index bodies index) state-id)
                     [:*const p/State])))

(az/defn state
  :- p/State
  []
  (body-state 0))

(az/defn sample
  :- contacts/Sample
  []
  (contacts/Sample {:bodies [(body-state 0) (body-state 1) (body-state 2)]}))

(az/defn publish!
  :- :void
  [[batch contacts/Sample]]
  (dotimes [i 3]
    (ecs/ecs_set_id world
                    (az/index bodies i)
                    state-id
                    (ak/sizeOf p/State)
                    (ak/& (az/index (az/field batch bodies) i)))))

(az/defn soft-state
  :- soft/Body
  [[index :u32]]
  (az/deref (az/cast (ecs/ecs_get_id world (az/index bodies index) soft-id)
                     [:*const soft/Body])))

(az/defn soft-sample
  :- soft/Sample
  []
  (soft/Sample {:bodies [(soft-state 0) (soft-state 1) (soft-state 2)]}))

(az/defn publish-soft!
  :- :void
  [[batch soft/Sample]]
  (dotimes [i 3]
    (ecs/ecs_set_id world
                    (az/index bodies i)
                    soft-id
                    (ak/sizeOf soft/Body)
                    (ak/& (az/index (az/field batch bodies) i)))))

(az/defn store-material!
  :- :void
  []
  (when (ak/== material-id 0)
    (set! material-id (component! "BallMaterial" (ak/sizeOf BallMaterial) (ak/alignOf BallMaterial))))
  (let [material (BallMaterial {:model (if continuum 2 (if deformable 1 0))
                                :young stiffness :poisson (if continuum fem/poisson 0.0)})]
    (set! _ (ecs/ecs_set_id world solver material-id (ak/sizeOf BallMaterial) (ak/& material)))))

(az/defn solver-settings
  :- BallMaterial
  []
  (az/deref (az/cast (ecs/ecs_get_id world solver material-id) [:*const BallMaterial])))

(az/defn reset!
  :- :void
  [[settings p/Config]]
  (clear-mesh-cache!)
  (az/set-many!
    solver-failed false
    minimum-jacobian 1.0)
  (store-material!)
  (ecs/ecs_set_id world source config-id (ak/sizeOf p/Config) (ak/& settings))
  (let [initial-sample (if (ak/== body-count 3)
                         (experiments/three-balls settings)
                         (experiments/single-ball settings))]
    (publish! initial-sample)
    (set! (az/index history 0) initial-sample)
    (let [initial-soft
          (soft/Sample
           {:bodies [(soft/initial (az/index (az/field initial-sample bodies) 0) settings)
                     (soft/initial (az/index (az/field initial-sample bodies) 1) settings)
                     (soft/initial (az/index (az/field initial-sample bodies) 2)
                                   settings)]})]
      (publish-soft! initial-soft)
      (set! (az/index soft-history 0) initial-soft)))
  (dotimes [i 3]
    (if (< i body-count)
      (ecs/ecs_remove_id world (az/index bodies i) ecs/EcsDisabled)
      (ecs/ecs_add_id world (az/index bodies i) ecs/EcsDisabled)))
  (az/set-many!
    count 1
    cursor 0
    revision (+ revision 1)))

(az/defn initialize!
  :- :void
  []
  (az/set-many!
    world (ecs/ecs_init)
    mesh-cache-id 0
    mesh-group-id 0
    config-id (component! "BallConfig" (ak/sizeOf p/Config) (ak/alignOf p/Config))
    state-id (component! "BallState" (ak/sizeOf p/State) (ak/alignOf p/State))
    soft-id (component! "DeformableBody" (ak/sizeOf soft/Body) (ak/alignOf soft/Body))
    material-id (component! "BallMaterial" (ak/sizeOf BallMaterial) (ak/alignOf BallMaterial))
    depends-on (entity! "DependsOn")
    source (entity! "sphere_source")
    solver (entity! "mechanical_solver")
    output (entity! "telemetry_output"))
  (dotimes [i 3]
    (set! (az/index bodies i)
          (entity! (if (ak/== i 0) "ball_01" (if (ak/== i 1) "ball_02" "ball_03"))))
    (ecs/ecs_add_id world
                    (az/index bodies i)
                    (ak/| ecs/ECS_PAIR (ak/<< ecs/EcsChildOf 32) solver)))
  (ecs/ecs_add_id world solver (ak/| ecs/ECS_PAIR (ak/<< depends-on 32) source))
  (ecs/ecs_add_id world output (ak/| ecs/ECS_PAIR (ak/<< depends-on 32) solver))
  (reset! (p/defaults)))

(az/defn seek!
  :- :void
  [[tick :u32]]
  (set! cursor (ak/min tick (- count 1)))
  (publish! (az/index history cursor))
  (when (and deformable (ak/== (mesh-cache) null))
    (publish-soft! (az/index soft-history cursor))))

(az/defn adopt-cache!
  "UI-thread ownership transfer. The output entity owns and releases this cache."
  :- :void
  [[owned [:* cache/Cache]]]
  (debug/assert (and (> (az/field owned count) 0) (<= (az/field owned count) capacity)
                     (ak/== (az/field owned count) (az/field (az/field owned frames) len))))
  (clear-mesh-cache!)
  (when (ak/== mesh-cache-id 0)
    (set! mesh-cache-id (component! "MeshCache" (ak/sizeOf MeshCacheRef) (ak/alignOf MeshCacheRef))))
  (let [reference (MeshCacheRef {:value owned})
        settings (az/field owned config)]
    (ecs/ecs_set_id world output mesh-cache-id (ak/sizeOf MeshCacheRef) (ak/& reference))
    (ecs/ecs_set_id world source config-id (ak/sizeOf p/Config) (ak/& settings))
    (az/set-many!
      body-count 1
      deformable true
      continuum true
      requested-continuum true
      stiffness (az/field owned young)
      count (az/field owned count)
      cursor 0
      solver-failed false
      revision (+ revision 1))
    (store-material!)
    (dotimes [tick count]
      (let [frame (cache/frame-info owned (ak/intCast tick))
            observation (az/field frame observation)
            ^:var summary (p/initial settings)]
        (az/set-many!
          (az/field summary position) (az/field observation center)
          (az/field summary velocity) (p/scale (az/field observation momentum) (/ 1.0 (az/field observation mass)))
          (az/field summary time) (az/field frame time)
          (az/index history tick) (contacts/Sample {:bodies [summary summary summary]}))))
    (dotimes [i 3]
      (if (ak/== i 0)
        (ecs/ecs_remove_id world (az/index bodies i) ecs/EcsDisabled)
        (ecs/ecs_add_id world (az/index bodies i) ecs/EcsDisabled)))
    (seek! 0)))

(az/defn adopt-group!
  "Publish a completed synchronized cache group; Flecs owns its full lifetime."
  :- :void
  [[owned [:* group/Group]]]
  (debug/assert (group/complete? owned))
  (debug/assert (ak/== (az/field owned count) 3))
  (clear-mesh-cache!)
  (when (ak/== mesh-group-id 0)
    (set! mesh-group-id (component! "MeshCacheGroup" (ak/sizeOf MeshGroupRef) (ak/alignOf MeshGroupRef))))
  (let [reference (MeshGroupRef {:value owned})
        first-cache (group/item owned 0)
        settings (az/field first-cache config)]
    (ecs/ecs_set_id world output mesh-group-id (ak/sizeOf MeshGroupRef) (ak/& reference))
    (ecs/ecs_set_id world source config-id (ak/sizeOf p/Config) (ak/& settings))
    (az/set-many!
      body-count 3
      deformable true
      continuum true
      requested-continuum true
      stiffness (az/field first-cache young)
      count (az/field first-cache count)
      cursor 0
      solver-failed false
      revision (+ revision 1))
    (store-material!)
    (dotimes [tick count]
      (dotimes [body 3]
        (let [item (group/item owned body)
              frame (cache/frame-info item (ak/intCast tick))
              observation (az/field frame observation)
              ^:var summary (p/initial (az/field item config))]
          (az/set-many!
            (az/field summary position) (az/field observation center)
            (az/field summary velocity) (p/scale (az/field observation momentum) (/ 1.0 (az/field observation mass)))
            (az/field summary time) (az/field frame time)
            (az/index (az/field (az/index history tick) bodies) body) summary))))
    (dotimes [body 3] (ecs/ecs_remove_id world (az/index bodies body) ecs/EcsDisabled))
    (seek! 0)))

(az/defn step!
  :- :bool
  []
  (when (ak/!= (mesh-cache) null) (ak/return false))
  (when (>= (+ cursor 1) capacity) (ak/return false))
  (let [material (solver-settings)]
    (az/set-many!
      continuum (ak/== (az/field material model) 2)
      deformable (ak/!= (az/field material model) 0)
      stiffness (az/field material young)))
  (let [settings (config)
        ^:var next (sample)]
    (if deformable
      (let [^{:var soft/Sample} next-soft ak/undefined]
        (if continuum
          (let [result (fem/advance (soft-sample) settings body-count stiffness dt)]
            (when (ak/! (az/field result completed))
              (set! solver-failed true)
              (ak/return false))
            (az/set-many!
              next-soft (az/field result sample)
              minimum-jacobian (ak/min minimum-jacobian (az/field result minimum-jacobian))))
          (set! next-soft (soft/advance (soft-sample) settings body-count stiffness dt)))
        (publish-soft! next-soft)
        (set! (az/index soft-history (+ cursor 1)) next-soft)
        (dotimes [i body-count]
          (let [body (az/index (az/field next-soft bodies) i)
                summary (ak/& (az/index (az/field next bodies) i))]
            (az/set-many!
              (az/field (az/deref summary) position) (soft/center body)
              (az/field (az/deref summary) velocity) (soft/velocity body)
              (az/field (az/deref summary) time) (+ (az/field (az/deref summary) time) dt)
              (az/field (az/deref summary) impulse) 0.0))))
      (if (ak/== body-count 1)
        (set! (az/index (az/field next bodies) 0) (p/advance (state) settings dt))
        (set! next (contacts/advance next settings body-count dt))))
    (az/set-many!
      cursor (+ cursor 1)
      count (+ cursor 1)
      (az/index history cursor) next)
    (publish! next)
    (set! _ (ecs/ecs_progress world (ak/floatCast dt))))
  true)

(az/defn set-experiment!
  :- :void
  [[three-balls :bool]]
  (set! body-count (if three-balls 3 1))
  (reset! (config)))

(az/defn set-material!
  :- :void
  [[enabled :bool] [modulus :f64]]
  (az/set-many!
    deformable enabled
    continuum (and enabled requested-continuum)
    stiffness (ak/max 1000.0 (ak/min 100000.0 modulus)))
  (reset! (config)))

(az/defn shutdown!
  :- :void
  []
  (clear-mesh-cache!)
  (when (ak/!= world null) (set! _ (ecs/ecs_fini world)) (set! world null)))

(az/defn set-solver!
  :- :void
  [[model :u32] [modulus :f64]]
  (set! requested-continuum (ak/== model 2))
  (set-material! (ak/!= model 0) modulus))

(az/defn bake-chunk!
  "Append numerical ticks independently of display time. Seeking never recomputes."
  :- :bool
  [[end-tick :u32] [budget :u32]]
  (let [last-tick (ak/min end-tick (ak/as :u32 (ak/intCast (- capacity 1))))]
    (seek! (- count 1))
    (dotimes [_ budget]
      (when (>= cursor last-tick) (ak/return true))
      (when (ak/! (step!)) (ak/return true)))
    (>= cursor last-tick)))
