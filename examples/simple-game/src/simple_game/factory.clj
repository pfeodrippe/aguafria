(ns simple-game.factory
  "Native Flecs-backed coco-house factory with cache-dense belt cells."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings.flecs :as flecs]))

(az/defconst grid-width :usize 32)

(az/defconst grid-height :usize 24)

(az/defconst cell-count :usize (* grid-width grid-height))

(az/defconst terrain-water :u8 0)

(az/defconst terrain-sand :u8 1)

(az/defconst terrain-calcada :u8 2)

(az/defconst terrain-mangrove :u8 3)

(az/defconst terrain-cobblestone :u8 4)

(az/defconst building-empty :u8 0)

(az/defconst building-belt :u8 1)

(az/defconst building-extractor :u8 2)

(az/defconst building-storage :u8 3)

(az/defconst building-assembler :u8 4)

(az/defconst building-splitter :u8 5)

(az/defconst building-coco-house :u8 6)

(az/defconst direction-east :u8 0)

(az/defconst direction-south :u8 1)

(az/defconst direction-west :u8 2)

(az/defconst direction-north :u8 3)

(az/defconst item-none :u8 0)

(az/defconst item-ice :u8 1)

(az/defconst item-coconut :u8 2)

(az/defconst item-cup :u8 3)

(az/defconst item-coco-panel :u8 4)

(az/defconst fixed-step :f32 0.008333333)

(az/defconst panels-per-house :u16 6)

(az/defconst house-goal :u32 5)

(az/defn harvest-duration
  "Seconds required for one harvester to produce a coconut."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  []
  0.65)

(az/defn press-duration
  "Seconds required for one press to turn a coconut into a panel."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  []
  0.35)

(az/defn house-panel-recipe
  "Number of panels consumed by one coco-house construction site."
  {:attrs #{:public :implicit-return}}
  :-
  :u16
  []
  panels-per-house)

(az/defstruct GridPosition
  "Flecs identity and 3D grid coordinate for a factory entity."
  {:layout :extern}
  [[:x :i16]
   [:y :i16]
   [:z :i16]])

(az/defstruct FactoryBuilding
  "Queryable immutable-ish description stored in Flecs tables."
  {:layout :extern}
  [[:kind :u8]
   [:direction :u8]
   [:recipe :u8]
   [:reserved :u8]])

(az/defstruct FactoryRuntime
  "Sparse Flecs state whose address stays stable across archetype moves."
  {:layout :extern}
  [[:progress :f32]
   [:energy :f32]
   [:items_processed :u32]
   [:ticks :u64]])

(az/defstruct Cell
  "Cache-dense belt simulation cell. Flecs remains the entity authority."
  {:layout :extern}
  [[:terrain :u8]
   [:building :u8]
   [:direction :u8]
   [:item_kind :u8]
   [:item_progress :f32]
   [:machine_progress :f32]
   [:inventory :u16]
   [:entity :u64]
   [:stable_address :usize]])

(az/defstruct CellView
  "Renderer/inspector projection of one factory cell."
  {:layout :extern}
  [[:valid :bool]
   [:x :i32]
   [:y :i32]
   [:terrain :u8]
   [:building :u8]
   [:direction :u8]
   [:item_kind :u8]
   [:item_progress :f32]
   [:inventory :u16]
   [:entity :u64]])

(az/defstruct FactorySnapshot
  "Compact native dashboard for the complete factory simulation."
  {:layout :extern}
  [[:initialized :bool]
   [:paused :bool]
   [:build_kind :u8]
   [:build_direction :u8]
   [:selected_x :i32]
   [:selected_y :i32]
   [:buildings :u32]
   [:belts :u32]
   [:items :u32]
   [:delivered :u32]
   [:coconuts_harvested :u32]
   [:panels_produced :u32]
   [:houses_started :u32]
   [:houses_completed :u32]
   [:house_goal :u32]
   [:objective_complete :bool]
   [:simulation_ticks :u64]
   [:last_substeps :u8]
   [:interpolation_alpha :f32]
   [:event_count :u64]
   [:stable_sample_address :usize]])

(az/defstruct FactoryEvent
  "Payload sent through Flecs' native event dispatcher."
  {:layout :extern}
  [[:kind :u8]
   [:building :u8]
   [:x :i16]
   [:y :i16]
   [:entity :u64]
   [:value :u32]])

(az/defvar factory-world [:optional [:* flecs/ecs_world_t]] null)

(az/defvar cells [:array 768 Cell]
  (std-mem/zeroes (az/type [:array 768 Cell])))

(az/defvar initialized false)

(az/defvar paused false)

(az/defvar build-kind :u8 building-belt)

(az/defvar build-direction :u8 direction-east)

(az/defvar selected-x :i32 -1)

(az/defvar selected-y :i32 -1)

(az/defvar accumulator :f32 0.0)

(az/defvar simulation-ticks :u64 0)

(az/defvar last-substeps :u8 0)

(az/defvar event-count :u64 0)

(az/defvar delivered-count :u32 0)

(az/defvar coconuts-harvested-count :u32 0)

(az/defvar panels-produced-count :u32 0)

(az/defvar houses-completed-count :u32 0)

(az/defvar position-component :u64 0)

(az/defvar building-component :u64 0)

(az/defvar runtime-component :u64 0)

(az/defvar simulation-system :u64 0)

(az/defvar installed-system-callback :usize 0)

(az/defvar factory-event-id :u64 0)

(az/defvar factory-observer-id :u64 0)

(az/defvar observed-event-count :u64 0)

(az/defn cell-index
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[x :i32]
   [y :i32]]
  (+ (ak/as :usize (ak/intCast x))
     (* (ak/as :usize (ak/intCast y)) grid-width)))

(az/defn valid-cell?
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[x :i32]
   [y :i32]]
  (and (>= x 0)
       (>= y 0)
       (< x (ak/as :i32 (ak/intCast grid-width)))
       (< y (ak/as :i32 (ak/intCast grid-height)))))

(az/defn register-component
  {:export false}
  :-
  :u64
  [[world [:* flecs/ecs_world_t]]
   [component-name [:pointer {:size :c :const? true} :u8]]
   [byte-size :usize]
   [byte-alignment :usize]]
  (let [entity-desc (flecs/ecs_entity_desc_t {:name component-name})
        component-entity (flecs/ecs_entity_init world (ak/& entity-desc))
        type-info (flecs/ecs_type_info_t
                   {:size (ak/intCast byte-size)
                    :alignment (ak/intCast byte-alignment)})
        component-desc (flecs/ecs_component_desc_t
                        {:entity component-entity :type type-info})]
    (flecs/ecs_component_init world (ak/& component-desc))))

(az/defn emit-event!
  "Publish a synchronous custom Flecs event with a stack-safe payload."
  {:export false}
  :-
  :void
  [[kind :u8]
   [building :u8]
   [x :i32]
   [y :i32]
   [entity :u64]
   [value :u32]]
  (when (ak/!= factory-world null)
    (let [payload (FactoryEvent
                   {:kind kind
                    :building building
                    :x (ak/intCast x)
                    :y (ak/intCast y)
                    :entity entity
                    :value value})
          descriptor (flecs/ecs_event_desc_t
                      {:event factory-event-id
                       :entity entity
                       :const_param (ak/& payload)})]
      (flecs/ecs_emit
       factory-world
       (ak/ptrCast (ak/constCast (ak/& descriptor))))
      (set! event-count (+ event-count 1)))))

(az/defn observe-factory-event
  "Flecs observer proving that construction events stay in the ECS event graph."
  :-
  :void
  [[iterator [:c-pointer flecs/ecs_iter_t]]]
  (set! _ iterator)
  (set! observed-event-count (+ observed-event-count 1)))

(az/defn install-event-observer!
  "Install the native observer used for inspectable factory-domain events."
  {:export false}
  :-
  :u64
  [[world [:* flecs/ecs_world_t]]]
  (let [^{:var true}
        descriptor
        (flecs/ecs_observer_desc_t
         {:query (flecs/ecs_query_desc_t {:expr "_"})
          :callback (ak/& observe-factory-event)})]
    (set! (az/index (az/field descriptor events) 0) factory-event-id)
    (flecs/ecs_observer_init world (ak/& descriptor))))

(az/defn observed-events
  "Return the number of custom factory events consumed by the Flecs observer."
  {:attrs #{:public :implicit-return}}
  :-
  :u64
  []
  observed-event-count)

(az/defn stable-runtime-address
  "Return the native address of a cell's sparse Flecs runtime component."
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[x :i32]
   [y :i32]]
  (if (valid-cell? x y)
    (az/field (az/index cells (cell-index x y)) stable_address)
    0))

(az/defn verify-stable-runtime!
  "Move an entity through an archetype and verify its sparse address is stable."
  :-
  :bool
  [[x :i32]
   [y :i32]]
  (if (or (ak/! (valid-cell? x y))
          (ak/== factory-world null))
    false
    (let [cell (az/index cells (cell-index x y))
          entity (az/field cell entity)
          before (az/field cell stable_address)]
      (if (or (ak/== entity 0) (ak/== before 0))
        false
        (let [tag (flecs/ecs_new factory-world)]
          (flecs/ecs_add_id factory-world entity tag)
          (let [during (-> factory-world
                           (flecs/ecs_get_sparse_id entity runtime-component
                                                    (ak/sizeOf FactoryRuntime))
                           (az/cast [:* FactoryRuntime])
                           ak/intFromPtr)]
            (flecs/ecs_remove_id factory-world entity tag)
            (let [after (-> factory-world
                            (flecs/ecs_get_sparse_id entity runtime-component
                                                     (ak/sizeOf FactoryRuntime))
                            (az/cast [:* FactoryRuntime])
                            ak/intFromPtr)]
              (flecs/ecs_delete factory-world tag)
              (and (ak/== before during) (ak/== before after)))))))))

(az/defn system-tick
  "Flecs system updating sparse, stable-address runtime components."
  :-
  :void
  [[iterator [:c-pointer flecs/ecs_iter_t]]]
  (let [buildings (-> iterator
                      (flecs/ecs_field_w_size (ak/sizeOf FactoryBuilding) 0)
                      (az/cast [:c-pointer FactoryBuilding]))]
    (dotimes [row (az/field (az/index iterator 0) count)]
      (let [building (ak/& (az/index buildings row))
            runtime (-> iterator
                        (flecs/ecs_field_at_w_size
                         (ak/sizeOf FactoryRuntime) 1 (ak/intCast row))
                        (az/cast [:* FactoryRuntime]))
            ^{:zig/type :f32}
            rate (if (ak/== (az/field (az/deref building) kind)
                            building-belt)
                   1.0
                   0.35)]
        (set! (az/field (az/deref runtime) progress)
              (+ (az/field (az/deref runtime) progress)
                 (* (az/field (az/index iterator 0) delta_time) rate)))
        (set! (az/field (az/deref runtime) ticks)
              (+ (az/field (az/deref runtime) ticks) 1))))))

(az/defn install-system!
  {:export false}
  :-
  :u64
  [[world [:* flecs/ecs_world_t]]]
  (let [descriptor
        (flecs/ecs_system_desc_t
         {:callback (ak/& system-tick)
          :phase flecs/EcsOnUpdate
          :query (flecs/ecs_query_desc_t
                  {:expr "FactoryBuilding, FactoryRuntime"})})]
    (flecs/ecs_system_init world (ak/& descriptor))))

(az/defn refresh-system-callback!
  "Point Flecs at the latest compatible system body after a development edit."
  :-
  :void
  []
  (when (and (ak/!= factory-world null) (ak/!= simulation-system 0))
    (let [callback (ak/& system-tick)
          callback-address (ak/intFromPtr callback)]
      (when (ak/!= installed-system-callback callback-address)
        (let [descriptor (flecs/ecs_system_desc_t {:callback callback})]
          (set! _ (flecs/ecs_system_update
                   factory-world simulation-system (ak/& descriptor)))
          (set! installed-system-callback callback-address))))))

(az/defn generate-terrain!
  "Create one buildable industrial floor for the coco-house factory."
  {:export false}
  :-
  :void
  []
  (dotimes [y grid-height]
    (dotimes [x grid-width]
      (let [terrain (if (ak/== (mod (+ x y) 5) 0)
                      terrain-cobblestone
                      terrain-calcada)]
        (set! (az/index cells (+ x (* y grid-width)))
              (Cell {:terrain terrain
                     :building building-empty
                     :direction direction-east
                     :item_kind item-none
                     :item_progress 0.0
                     :machine_progress 0.0
                     :inventory 0
                     :entity 0
                     :stable_address 0}))))))

(az/defn place!
  "Construct one Flecs entity and attach it to the dense simulation cell."
  :-
  :bool
  [[x :i32]
   [y :i32]
   [kind :u8]
   [direction :u8]]
  (if (or (ak/! (valid-cell? x y))
          (ak/== factory-world null))
    false
    (let [cell (ak/& (az/index cells (cell-index x y)))]
      (if (or (ak/!= (az/field (az/deref cell) building) building-empty)
              (ak/== (az/field (az/deref cell) terrain) terrain-water))
        false
        (let [world (az/unwrap factory-world)
              entity (flecs/ecs_new world)
              position (GridPosition {:x (ak/intCast x)
                                      :y (ak/intCast y)
                                      :z 0})
              building (FactoryBuilding {:kind kind
                                         :direction (mod direction 4)
                                         :recipe (if (ak/== kind building-assembler)
                                                   1
                                                   0)
                                         :reserved 0})
              runtime (FactoryRuntime {:progress 0.0
                                       :energy 1.0
                                       :items_processed 0
                                       :ticks 0})]
          (flecs/ecs_set_id world entity position-component
                            (ak/sizeOf GridPosition) (ak/& position))
          (flecs/ecs_set_id world entity building-component
                            (ak/sizeOf FactoryBuilding) (ak/& building))
          (flecs/ecs_set_id world entity runtime-component
                            (ak/sizeOf FactoryRuntime) (ak/& runtime))
          (let [stable (-> world
                           (flecs/ecs_get_sparse_id entity runtime-component
                                                    (ak/sizeOf FactoryRuntime))
                           (az/cast [:* FactoryRuntime]))]
            (set! (az/field (az/deref cell) building) kind)
            (set! (az/field (az/deref cell) direction) (mod direction 4))
            (set! (az/field (az/deref cell) entity) entity)
            (set! (az/field (az/deref cell) stable_address)
                  (ak/intFromPtr stable)))
          (emit-event! 1 kind x y entity 0)
          true)))))

(az/defn remove!
  "Demolish one entity while retaining terrain and deterministic cell identity."
  :-
  :bool
  [[x :i32]
   [y :i32]]
  (if (or (ak/! (valid-cell? x y))
          (ak/== factory-world null))
    false
    (let [cell (ak/& (az/index cells (cell-index x y)))
          entity (az/field (az/deref cell) entity)
          kind (az/field (az/deref cell) building)]
      (if (ak/== kind building-empty)
        false
        (do
          (emit-event! 2 kind x y entity 0)
          (flecs/ecs_delete factory-world entity)
          (set! (az/field (az/deref cell) building) building-empty)
          (set! (az/field (az/deref cell) item_kind) item-none)
          (set! (az/field (az/deref cell) item_progress) 0.0)
          (set! (az/field (az/deref cell) machine_progress) 0.0)
          (set! (az/field (az/deref cell) inventory) 0)
          (set! (az/field (az/deref cell) entity) 0)
          (set! (az/field (az/deref cell) stable_address) 0)
          true)))))

(az/defn destination
  "Return the neighboring cell index or the source index at a map edge."
  {:export false}
  :-
  :usize
  [[x :i32]
   [y :i32]
   [direction :u8]]
  (let [next-x (cond
                 (ak/== direction direction-east) (+ x 1)
                 (ak/== direction direction-west) (- x 1)
                 :else x)
        next-y (cond
                 (ak/== direction direction-south) (+ y 1)
                 (ak/== direction direction-north) (- y 1)
                 :else y)]
    (if (valid-cell? next-x next-y)
      (cell-index next-x next-y)
      (cell-index x y))))

(az/defn cell-receives-item?
  "Return whether one routed item can enter a target cell right now."
  {:export false :implicit-return true}
  :-
  :bool
  [[source-index :usize]
   [target-index :usize]
   [item-kind :u8]]
  (let [target (az/index cells target-index)
        target-kind (az/field target building)]
    (and
     (ak/!= target-index source-index)
     (or
      (and (ak/== target-kind building-coco-house)
           (ak/== item-kind item-coco-panel)
           (< (az/field target inventory) (house-panel-recipe)))
      (ak/== target-kind building-storage)
      (and (ak/!= target-kind building-empty)
           (ak/!= target-kind building-coco-house)
           (ak/== (az/field target item_kind) item-none))))))

(az/defn move-item!
  {:export false}
  :-
  :void
  [[source-index :usize]
   [x :i32]
   [y :i32]
   [step :f32]]
  (let [source (ak/& (az/index cells source-index))
        source-kind (az/field (az/deref source) building)]
    (when (ak/!= (az/field (az/deref source) item_kind) item-none)
      (set! (az/field (az/deref source) item_progress)
            (+ (az/field (az/deref source) item_progress)
               (* step 2.4)))
      (when (>= (az/field (az/deref source) item_progress) 1.0)
        (let [direction (az/field (az/deref source) direction)
              item-kind (az/field (az/deref source) item_kind)
              forward-index (destination x y direction)
              preferred-direction
              (if (< (az/field (az/deref source) machine_progress) 0.5)
                (mod (+ direction 3) 4)
                (mod (+ direction 1) 4))
              alternate-direction
              (if (< (az/field (az/deref source) machine_progress) 0.5)
                (mod (+ direction 1) 4)
                (mod (+ direction 3) 4))
              preferred-index (destination x y preferred-direction)
              alternate-index (destination x y alternate-direction)
              target-index
              (if (ak/== source-kind building-splitter)
                (cond
                  (cell-receives-item? source-index preferred-index item-kind)
                  preferred-index

                  (cell-receives-item? source-index alternate-index item-kind)
                  alternate-index

                  :else source-index)
                forward-index)
              target (ak/& (az/index cells target-index))
              target-kind (az/field (az/deref target) building)
              ^{:var true :zig/type :bool} moved false]
          (cond
            (and (ak/!= target-index source-index)
                 (ak/== target-kind building-coco-house)
                 (ak/== (az/field (az/deref source) item_kind)
                        item-coco-panel)
                 (< (az/field (az/deref target) inventory)
                    (house-panel-recipe)))
            (do
              (set! (az/field (az/deref target) inventory)
                    (+ (az/field (az/deref target) inventory) 1))
              (set! (az/field (az/deref source) item_kind) item-none)
              (set! (az/field (az/deref source) item_progress) 0.0)
              (set! moved true)
              (when (and (>= (az/field (az/deref target) inventory)
                             (house-panel-recipe))
                         (< (az/field (az/deref target) machine_progress) 1.0))
                (set! (az/field (az/deref target) machine_progress) 1.0)
                (set! houses-completed-count (+ houses-completed-count 1))
                (emit-event! 4 target-kind x y
                             (az/field (az/deref target) entity)
                             houses-completed-count)))

            (and (ak/!= target-index source-index)
                 (ak/== target-kind building-storage))
            (do
              (set! (az/field (az/deref target) inventory)
                    (+ (az/field (az/deref target) inventory) 1))
              (set! delivered-count (+ delivered-count 1))
              (emit-event! 3 target-kind x y
                           (az/field (az/deref target) entity)
                           delivered-count)
              (set! (az/field (az/deref source) item_kind) item-none)
              (set! (az/field (az/deref source) item_progress) 0.0)
              (set! moved true))

            (and (ak/!= target-index source-index)
                 (ak/!= target-kind building-empty)
                 (ak/!= target-kind building-coco-house)
                 (ak/== (az/field (az/deref target) item_kind) item-none))
            (do
              (set! (az/field (az/deref target) item_kind)
                    (az/field (az/deref source) item_kind))
              (set! (az/field (az/deref target) item_progress) 0.0)
              (set! (az/field (az/deref source) item_kind) item-none)
              (set! (az/field (az/deref source) item_progress) 0.0)
              (set! moved true))

            :else
            (set! (az/field (az/deref source) item_progress) 1.0))
          (when (and moved (ak/== source-kind building-splitter))
            (set! (az/field (az/deref source) machine_progress)
                  (if (< (az/field (az/deref source) machine_progress) 0.5)
                    1.0
                    0.0))))))))

(az/defn simulate-step!
  "One allocation-free fixed simulation step over the dense factory grid."
  {:export false}
  :-
  :void
  []
  (dotimes [y grid-height]
    (dotimes [x grid-width]
      (let [index (+ x (* y grid-width))
            cell (ak/& (az/index cells index))
            kind (az/field (az/deref cell) building)]
        (cond
          (ak/== kind building-extractor)
          (do
            (set! (az/field (az/deref cell) machine_progress)
                  (+ (az/field (az/deref cell) machine_progress) fixed-step))
            (when (and (>= (az/field (az/deref cell) machine_progress)
                            (harvest-duration))
                       (ak/== (az/field (az/deref cell) item_kind) item-none))
              (set! (az/field (az/deref cell) machine_progress) 0.0)
              (set! (az/field (az/deref cell) item_kind) item-coconut)
              (set! (az/field (az/deref cell) item_progress) 0.0)
              (set! coconuts-harvested-count (+ coconuts-harvested-count 1)))
            (move-item! index
                        (ak/as :i32 (ak/intCast x))
                        (ak/as :i32 (ak/intCast y))
                        fixed-step))

          (ak/== kind building-assembler)
          (if (ak/== (az/field (az/deref cell) item_kind) item-coconut)
            (do
              (set! (az/field (az/deref cell) machine_progress)
                    (+ (az/field (az/deref cell) machine_progress) fixed-step))
              (when (>= (az/field (az/deref cell) machine_progress)
                        (press-duration))
                (set! (az/field (az/deref cell) machine_progress) 0.0)
                (set! (az/field (az/deref cell) item_kind) item-coco-panel)
                (set! (az/field (az/deref cell) item_progress) 0.0)
                (set! panels-produced-count (+ panels-produced-count 1))))
            (move-item! index
                        (ak/as :i32 (ak/intCast x))
                        (ak/as :i32 (ak/intCast y))
                        fixed-step))

          (and (ak/!= kind building-empty)
               (ak/!= kind building-coco-house))
          (move-item! index
                      (ak/as :i32 (ak/intCast x))
                      (ak/as :i32 (ak/intCast y))
                      fixed-step)))))
  (set! simulation-ticks (+ simulation-ticks 1)))

(az/defn step!
  "Bounded fixed-timestep factory progression for stable, repeatable behavior."
  :-
  :void
  [[delta-seconds :f32]]
  (when (and initialized (ak/! paused))
    (set! accumulator (+ accumulator (ak/min 0.1 (ak/max 0.0 delta-seconds))))
    (let [^{:var true :zig/type :u8} substeps 0]
      (ak/while (and (>= accumulator fixed-step) (< substeps 8))
        (simulate-step!)
        (set! accumulator (- accumulator fixed-step))
        (set! substeps (+ substeps 1)))
      (set! last-substeps substeps))))

(az/defn screen-to-cell
  "Inverse the shared isometric projection used by the desktop renderer."
  :-
  CellView
  [[pointer-x :f32]
   [pointer-y :f32]]
  (let [camera-x (/ (- pointer-x 360.0) 39.0)
        camera-z (/ (- pointer-y 250.0) 18.0)
        world-x (/ (+ camera-z camera-x) 2.0)
        world-z (/ (- camera-z camera-x) 2.0)
        x (ak/as :i32 (ak/intFromFloat (+ (/ world-x 0.5) 11.5)))
        y (ak/as :i32 (ak/intFromFloat (+ (/ world-z 0.5) 12.5)))]
    (if (valid-cell? x y)
      (let [cell (az/index cells (cell-index x y))]
        (CellView {:valid true
                   :x x :y y
                   :terrain (az/field cell terrain)
                   :building (az/field cell building)
                   :direction (az/field cell direction)
                   :item_kind (az/field cell item_kind)
                   :item_progress (az/field cell item_progress)
                   :inventory (az/field cell inventory)
                   :entity (az/field cell entity)}))
      (CellView {:valid false
                 :x -1 :y -1
                 :terrain terrain-water
                 :building building-empty
                 :direction direction-east
                 :item_kind item-none
                 :item_progress 0.0
                 :inventory 0
                 :entity 0}))))

(az/defn handle-pointer!
  "Select a tile and optionally construct with the active tool."
  :-
  :bool
  [[pointer-x :f32]
   [pointer-y :f32]
   [pressed :bool]]
  (let [view (screen-to-cell pointer-x pointer-y)]
    (set! selected-x (az/field view x))
    (set! selected-y (az/field view y))
    (and pressed
         (az/field view valid)
         (place! selected-x selected-y build-kind build-direction))))

(az/defn rotate-build!
  :-
  :u8
  [[clockwise :bool]]
  (set! build-direction
        (if clockwise
          (mod (+ build-direction 1) 4)
          (mod (+ build-direction 3) 4)))
  build-direction)

(az/defn set-build-kind!
  :-
  :void
  [[kind :u8]]
  (when (<= kind building-coco-house)
    (set! build-kind kind)))

(az/defn toggle-paused!
  :-
  :bool
  []
  (set! paused (ak/! paused))
  paused)

(az/defn cell-view
  :-
  CellView
  [[x :i32]
   [y :i32]]
  (if (valid-cell? x y)
    (let [cell (az/index cells (cell-index x y))]
      (CellView {:valid true
                 :x x :y y
                 :terrain (az/field cell terrain)
                 :building (az/field cell building)
                 :direction (az/field cell direction)
                 :item_kind (az/field cell item_kind)
                 :item_progress (az/field cell item_progress)
                 :inventory (az/field cell inventory)
                 :entity (az/field cell entity)}))
    (CellView {:valid false
               :x x :y y
               :terrain terrain-water
               :building building-empty
               :direction direction-east
               :item_kind item-none
               :item_progress 0.0
               :inventory 0
               :entity 0})))

(az/defn initialize!
  "Register Flecs components and seed the first coco-house production line."
  :-
  :bool
  [[world [:* flecs/ecs_world_t]]]
  (when (ak/! initialized)
    (set! factory-world world)
    (generate-terrain!)
    (set! position-component
          (register-component world "FactoryGridPosition"
                              (ak/sizeOf GridPosition) (ak/alignOf GridPosition)))
    (set! building-component
          (register-component world "FactoryBuilding"
                              (ak/sizeOf FactoryBuilding)
                              (ak/alignOf FactoryBuilding)))
    (set! runtime-component
          (register-component world "FactoryRuntime"
                              (ak/sizeOf FactoryRuntime) (ak/alignOf FactoryRuntime)))
    ;; Flecs Sparse is the current stable-pointer component storage mechanism.
    (flecs/ecs_add_id world runtime-component flecs/EcsSparse)
    (set! factory-event-id (flecs/ecs_new world))
    (set! factory-observer-id (install-event-observer! world))
    (set! simulation-system (install-system! world))
    (set! installed-system-callback (ak/intFromPtr (ak/& system-tick)))
    (set! initialized true)
    ;; A complete working line makes the first frame immediately understandable.
    (set! _ (place! 4 12 building-extractor direction-east))
    (dotimes [offset 4]
      (set! _ (place! (+ 5 (ak/as :i32 (ak/intCast offset))) 12
                       building-belt direction-east)))
    (set! _ (place! 9 12 building-assembler direction-east))
    (dotimes [offset 5]
      (set! _ (place! (+ 10 (ak/as :i32 (ak/intCast offset))) 12
                       building-belt direction-east)))
    (set! _ (place! 15 12 building-coco-house direction-east)))
  initialized)

(az/defn snapshot
  "Inspect factory occupancy, objective progress, and stable Flecs storage."
  :-
  FactorySnapshot
  []
  (let [^{:var true :zig/type :u32} buildings 0
        ^{:var true :zig/type :u32} belts 0
        ^{:var true :zig/type :u32} items 0
        ^{:var true :zig/type :u32} houses-started 0
        ^{:var true :zig/type :usize} stable-sample 0]
    (dotimes [index cell-count]
      (let [cell (az/index cells index)]
        (when (ak/!= (az/field cell building) building-empty)
          (set! buildings (+ buildings 1)))
        (when (ak/== (az/field cell building) building-belt)
          (set! belts (+ belts 1)))
        (when (ak/!= (az/field cell item_kind) item-none)
          (set! items (+ items 1)))
        (when (ak/== (az/field cell building) building-coco-house)
          (set! houses-started (+ houses-started 1)))
        (when (and (ak/== stable-sample 0)
                   (ak/!= (az/field cell stable_address) 0))
          (set! stable-sample (az/field cell stable_address)))))
    (FactorySnapshot
     {:initialized initialized
      :paused paused
      :build_kind build-kind
      :build_direction build-direction
      :selected_x selected-x
      :selected_y selected-y
      :buildings buildings
      :belts belts
      :items items
      :delivered delivered-count
      :coconuts_harvested coconuts-harvested-count
      :panels_produced panels-produced-count
      :houses_started houses-started
      :houses_completed houses-completed-count
      :house_goal house-goal
      :objective_complete (>= houses-completed-count house-goal)
      :simulation_ticks simulation-ticks
      :last_substeps last-substeps
      :interpolation_alpha (/ accumulator fixed-step)
      :event_count event-count
      :stable_sample_address stable-sample})))

(az/defn shutdown!
  "Forget factory state; the owning game destroys the Flecs world itself."
  :-
  :void
  []
  (set! cells (std-mem/zeroes (az/type [:array 768 Cell])))
  (set! factory-world null)
  (set! initialized false)
  (set! paused false)
  (set! accumulator 0.0)
  (set! simulation-ticks 0)
  (set! last-substeps 0)
  (set! event-count 0)
  (set! delivered-count 0)
  (set! coconuts-harvested-count 0)
  (set! panels-produced-count 0)
  (set! houses-completed-count 0)
  (set! position-component 0)
  (set! building-component 0)
  (set! runtime-component 0)
  (set! simulation-system 0)
  (set! installed-system-callback 0)
  (set! factory-event-id 0)
  (set! factory-observer-id 0)
  (set! observed-event-count 0))
