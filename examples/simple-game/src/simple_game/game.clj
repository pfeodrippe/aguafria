(ns simple-game.game
  "Hot-reloadable Flecs gameplay, written entirely as Aguafria Zig forms."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [simple-game.audio :as audio]
            [simple-game.bindings]
            [simple-game.bindings.flecs :as flecs]
            [simple-game.physics :as physics]))

(az/defstruct Position
  "Circle center in framebuffer pixels."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]])

(az/defstruct Circle
  "Animated circle state stored by Flecs."
  {:layout :extern}
  [[:radius :f32]
   [:phase :f32]
   [:hovered :bool]])

(az/defstruct Counter
  "Click count and repeating shader selection stored by Flecs."
  {:layout :extern}
  [[:value :u32]
   [:shader_index :u32]])

(az/defstruct Input
  "Platform-neutral input for one game tick."
  {:layout :extern}
  [[:pointer_x :f32]
   [:pointer_y :f32]
   [:viewport_width :f32]
   [:viewport_height :f32]
   [:delta_seconds :f32]
   [:pointer_down :bool]
   [:pointer_pressed :bool]])

(az/defstruct RenderPacket
  "Complete platform-neutral render state returned by a tick."
  {:layout :extern}
  [[:center_x :f32]
   [:center_y :f32]
   [:radius :f32]
   [:phase :f32]
   [:counter :u32]
   [:shader_index :u32]
   [:hovered :bool]])

(az/defstruct WorldSnapshot
  "Small live Flecs/game view intended for nREPL inspection."
  {:layout :extern}
  [[:initialized :bool]
   [:entity :u64]
   [:system :u64]
   [:counter :u32]
   [:shader_index :u32]
   [:phase :f32]
   [:hovered :bool]])

(az/defvar world [:optional [:* flecs/ecs_world_t]] null)

(az/defvar circle_entity :u64 0)

(az/defvar position_component :u64 0)

(az/defvar circle_component :u64 0)

(az/defvar counter_component :u64 0)

(az/defvar update_system :u64 0)

(az/defvar installed_callback :usize 0)

(az/defconst target-fps :f32 120.0)

(az/defvar current_input Input
  (Input {:pointer_x 0.0
          :pointer_y 0.0
          :viewport_width 0.0
          :viewport_height 0.0
          :delta_seconds 0.0
          :pointer_down false
          :pointer_pressed false}))

(az/defn register-component
  {:attrs #{:public :implicit-return}}
  :-
  :u64
  [[flecs-world [:* flecs/ecs_world_t]]
   [component-name [:pointer {:size :c :const? true} :u8]]
   [byte-size :usize]
   [byte-alignment :usize]]
  (let [entity-desc (flecs/ecs_entity_desc_t
                     {:name component-name})
        component-entity (flecs/ecs_entity_init flecs-world (ak/& entity-desc))
        type-info (flecs/ecs_type_info_t
                   {:size (ak/intCast byte-size)
                    :alignment (ak/intCast byte-alignment)})
        component-desc (flecs/ecs_component_desc_t
                        {:entity component-entity
                         :type type-info})]
    (flecs/ecs_component_init flecs-world (ak/& component-desc))))

(az/defn circle-contains?
  "Pure circle hit test used by the Flecs system."
  :-
  :bool
  [[pointer-x :f32]
   [pointer-y :f32]
   [center-x :f32]
   [center-y :f32]
   [radius :f32]]
  (let [dx (- pointer-x center-x)
        dy (- pointer-y center-y)]
    (<= (+ (* dx dx) (* dy dy)) (* radius radius))))

(az/defn animated-phase
  "Pure animation transition; hovering advances the shader phase."
  :-
  :f32
  [[phase :f32]
   [delta-seconds :f32]
   [hovered :bool]]
  (if hovered
    (+ phase (* delta-seconds 4.0))
    phase))

(az/defn shader-for-count
  "Choose one of the five repeating shader treatments."
  :-
  :u32
  [[counter :u32]]
  (mod counter 5))

(az/defn advance-counter!
  "Apply the shared click transition to one Flecs Counter component."
  :-
  :u32
  [[counter [:c-pointer Counter]]]
  (let [next-count (+ (az/field (az/deref counter) value) 1)]
    (set! (az/field (az/deref counter) value) next-count)
    (set! (az/field (az/deref counter) shader_index)
          (shader-for-count next-count))
    (physics/emit! (shader-for-count next-count))
    (audio/play-click! next-count)
    next-count))

(az/defn update-circle-system
  "Flecs invokes this stable Aguafria dispatch function for matching entities."
  :-
  :void
  [[iterator [:c-pointer flecs/ecs_iter_t]]]
  (let [positions (-> iterator
                      (flecs/ecs_field_w_size (ak/sizeOf Position) 0)
                      (az/cast [:c-pointer Position]))
        circles (-> iterator
                    (flecs/ecs_field_w_size (ak/sizeOf Circle) 1)
                    (az/cast [:c-pointer Circle]))
        counters (-> iterator
                     (flecs/ecs_field_w_size (ak/sizeOf Counter) 2)
                     (az/cast [:c-pointer Counter]))]
    (dotimes [row (az/field (az/index iterator 0) count)]
      (let [position (ak/& (az/index positions row))
            circle (ak/& (az/index circles row))
            counter (ak/& (az/index counters row))
            hovered (circle-contains?
                     (az/field current_input pointer_x)
                     (az/field current_input pointer_y)
                     (az/field (az/deref position) x)
                     (az/field (az/deref position) y)
                     (az/field (az/deref circle) radius))]
        (set! (az/field (az/deref circle) hovered) hovered)
        (set! (az/field (az/deref circle) phase)
              (animated-phase
               (az/field (az/deref circle) phase)
               (az/field (az/index iterator 0) delta_time)
               hovered))
        (when (and hovered (az/field current_input pointer_pressed))
          (set! _ (advance-counter! counter)))))))

(az/defn install-system!
  {:attrs #{:public :implicit-return}}
  :-
  :u64
  [[flecs-world [:* flecs/ecs_world_t]]]
  (let [descriptor
        (flecs/ecs_system_desc_t
         {:callback (ak/& update-circle-system)
          :phase flecs/EcsOnUpdate
          :query (flecs/ecs_query_desc_t
                  {:expr "Position, Circle, Counter"})})]
    (flecs/ecs_system_init flecs-world (ak/& descriptor))))

(az/defn refresh-system-callback!
  "Keep a callback retained by Flecs pointed at the current dev generation.
  The caller is guarded by a compile-time Debug check, so standalone
  ReleaseFast builds contain no per-frame callback-refresh work."
  {:attrs #{:public}}
  :-
  :void
  [[flecs-world [:* flecs/ecs_world_t]]]
  (let [callback (ak/& update-circle-system)
        callback-address (ak/intFromPtr callback)]
    (when (ak/!= installed_callback callback-address)
      (let [descriptor (flecs/ecs_system_desc_t {:callback callback})]
        (set! _ (flecs/ecs_system_update
                 flecs-world update_system (ak/& descriptor)))
        (set! installed_callback callback-address)))))

(az/defn set-target-fps!
  "Set Flecs frame pacing; zero lets the platform drive the loop."
  :-
  :void
  [[fps :f32]]
  (when (ak/!= world null)
    (flecs/ecs_set_target_fps world fps)))

(az/defn configure-target-fps!
  "Ask Flecs to pace the live desktop world at 120 frames per second."
  :-
  :void
  []
  (set-target-fps! target-fps))

(az/defn current-fps
  "Return Flecs' measured real frame rate for the live world."
  :-
  :f32
  []
  (if (ak/== world null)
    0.0
    (let [info (flecs/ecs_get_world_info world)
          delta-time (az/field (az/deref info) delta_time_raw)]
      (if (> delta-time 0.0)
        (/ 1.0 delta-time)
        0.0))))

(az/defn initialize!
  "Create the live Flecs world once. Repeated REPL calls preserve its state."
  :-
  :bool
  []
  (when (ak/== world null)
    (set! world (flecs/ecs_init))
    (configure-target-fps!)
    (let [flecs-world (az/unwrap world)]
      (set! position_component
            (register-component flecs-world "Position"
                                (ak/sizeOf Position) (ak/alignOf Position)))
      (set! circle_component
            (register-component flecs-world "Circle"
                                (ak/sizeOf Circle) (ak/alignOf Circle)))
      (set! counter_component
            (register-component flecs-world "Counter"
                                (ak/sizeOf Counter) (ak/alignOf Counter)))
      (set! circle_entity (flecs/ecs_new flecs-world))
      (let [position (Position {:x 360.0 :y 270.0})
            circle (Circle {:radius 90.0
                            :phase 0.0
                            :hovered false})
            counter (Counter {:value 0 :shader_index 0})]
        (flecs/ecs_set_id flecs-world circle_entity position_component
                          (ak/sizeOf Position) (ak/& position))
        (flecs/ecs_set_id flecs-world circle_entity circle_component
                          (ak/sizeOf Circle) (ak/& circle))
        (flecs/ecs_set_id flecs-world circle_entity counter_component
                          (ak/sizeOf Counter) (ak/& counter)))
      (set! update_system (install-system! flecs-world))
      (set! installed_callback (ak/intFromPtr (ak/& update-circle-system)))))
  (ak/!= world null))

(az/defn tick
  "Run one real Flecs frame and return the renderer-independent result."
  :-
  RenderPacket
  [[pointer-x :f32]
   [pointer-y :f32]
   [viewport-width :f32]
   [viewport-height :f32]
   [delta-seconds :f32]
   [pointer-down :bool]
   [pointer-pressed :bool]]
  (set! _ (initialize!))
  (set! current_input
        (az/object [[:pointer_x pointer-x]
                    [:pointer_y pointer-y]
                    [:viewport_width viewport-width]
                    [:viewport_height viewport-height]
                    [:delta_seconds delta-seconds]
                    [:pointer_down pointer-down]
                    [:pointer_pressed pointer-pressed]]))
  (let [flecs-world (az/unwrap world)]
    (when (ak/== (az/field (ak/import "builtin") mode) :.Debug)
      (refresh-system-callback! flecs-world))
    (set! _ (flecs/ecs_progress flecs-world delta-seconds))
    (let [frame-info (flecs/ecs_get_world_info flecs-world)]
      (physics/step! (az/field (az/deref frame-info) delta_time)))
    (let [position (-> flecs-world
                       (flecs/ecs_get_mut_id circle_entity position_component)
                       (az/cast [:* Position]))
          circle (-> flecs-world
                     (flecs/ecs_get_mut_id circle_entity circle_component)
                     (az/cast [:* Circle]))
          counter (-> flecs-world
                      (flecs/ecs_get_mut_id circle_entity counter_component)
                      (az/cast [:* Counter]))]
      (RenderPacket
       {:center_x (az/field (az/deref position) x)
        :center_y (az/field (az/deref position) y)
        :radius (az/field (az/deref circle) radius)
        :phase (az/field (az/deref circle) phase)
        :counter (az/field (az/deref counter) value)
        :shader_index (az/field (az/deref counter) shader_index)
        :hovered (az/field (az/deref circle) hovered)}))))

(az/defn tick-auto
  "Run one platform-timed Flecs frame; Flecs measures the authoritative delta."
  :-
  RenderPacket
  [[pointer-x :f32]
   [pointer-y :f32]
   [viewport-width :f32]
   [viewport-height :f32]
   [pointer-down :bool]
   [pointer-pressed :bool]]
  (tick pointer-x pointer-y viewport-width viewport-height
        0.0 pointer-down pointer-pressed))

(az/defn click!
  "Apply one click to the live Flecs entity from nREPL and return its counter."
  :- :u32
  []
  (set! _ (initialize!))
  (let [flecs-world (az/unwrap world)
        counter (-> flecs-world
                    (flecs/ecs_get_mut_id circle_entity counter_component)
                    (az/cast [:* Counter]))]
    (advance-counter! counter)))

(az/defn snapshot
  "Inspect authoritative live native state without advancing the game."
  :- WorldSnapshot
  []
  (if (ak/== world null)
    (WorldSnapshot {:initialized false
                    :entity 0
                    :system 0
                    :counter 0
                    :shader_index 0
                    :phase 0.0
                    :hovered false})
    (let [flecs-world (az/unwrap world)
          circle (-> flecs-world
                     (flecs/ecs_get_mut_id circle_entity circle_component)
                     (az/cast [:* Circle]))
          counter (-> flecs-world
                      (flecs/ecs_get_mut_id circle_entity counter_component)
                      (az/cast [:* Counter]))]
      (WorldSnapshot
       {:initialized true
        :entity circle_entity
        :system update_system
        :counter (az/field (az/deref counter) value)
        :shader_index (az/field (az/deref counter) shader_index)
        :phase (az/field (az/deref circle) phase)
        :hovered (az/field (az/deref circle) hovered)}))))

(az/defn shutdown
  "Destroy the live Flecs world. The normal hot-reload workflow does not call this."
  {:attrs #{:public}}
  :-
  :void
  []
  (when (ak/!= world null)
    (set! _ (flecs/ecs_fini world))
    (set! world null)
    (set! circle_entity 0)
    (set! update_system 0)
    (set! installed_callback 0))
  (physics/shutdown!)
  (audio/shutdown!))
