(ns simple-game.game
  "Minimal Flecs owner and hot-reloadable coco-factory frame driver."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.atomic :as std-atomic]
            [aguafria.zig :as az]
            [simple-game.audio :as audio]
            [simple-game.bindings]
            [simple-game.bindings.flecs :as flecs]
            [simple-game.factory :as factory]
            [simple-game.physics :as physics]))

(az/defstruct RenderPacket
  "Small platform-neutral result passed from simulation to presentation."
  {:layout :extern}
  [[:frame :u64]])

(az/defstruct WorldSnapshot
  "Inspectable native owner state for the live factory world."
  {:layout :extern}
  [[:initialized :bool]
   [:frames :u64]
   [:fps :f32]
   [:buildings :u32]
   [:houses_completed :u32]
   [:world_address :usize]])

(az/defvar world [:optional [:* flecs/ecs_world_t]] null)

(az/defvar state-lock :u8 0)

(az/defvar frame-count :u64 0)

(az/defvar last-frame-work-seconds-value :f64 0.0)

(az/defvar handled-event-count :u64 0)

(az/defconst target-fps :f32 120.0)

(az/defn lock-state!
  "Serialize nREPL inspection with the native frame thread."
  :-
  :void
  []
  (ak/while
   (ak/!= (ak/atomicRmw :u8 (ak/& state-lock) :.Xchg 1 :.acquire) 0)
   (std-atomic/spinLoopHint)))

(az/defn unlock-state!
  :-
  :void
  []
  (ak/atomicStore :u8 (ak/& state-lock) 0 :.release))

(az/defn set-target-fps!
  "Set Flecs frame pacing; zero lets the platform run without a cap."
  :-
  :void
  [[fps :f32]]
  (when (ak/!= world null)
    (flecs/ecs_set_target_fps world fps)))

(az/defn configure-target-fps!
  "Pace the live desktop world at 120 frames per second."
  :-
  :void
  []
  (set-target-fps! target-fps))

(az/defn current-fps
  "Return Flecs' measured frame rate for the live factory world."
  :-
  :f32
  []
  (lock-state!)
  (let [result
        (if (ak/== world null)
          0.0
          (let [info (flecs/ecs_get_world_info world)
                delta-time (az/field (az/deref info) delta_time_raw)]
            (if (> delta-time 0.0) (/ 1.0 delta-time) 0.0)))]
    (unlock-state!)
    result))

(az/defn last-frame-work-seconds
  "Return simulation work for the latest frame, excluding Flecs pacing."
  :-
  :f64
  []
  last-frame-work-seconds-value)

(az/defn initialize!
  "Create one Flecs world and seed the coco-house line exactly once."
  :-
  :bool
  []
  (when (ak/== world null)
    (set! world (flecs/ecs_init))
    (configure-target-fps!)
    (set! _ (factory/initialize! (az/unwrap world)))
    (set! _ (physics/initialize!))
    (set! handled-event-count
          (az/field (factory/snapshot) event_count)))
  (ak/!= world null))

(az/defn process-factory-event!
  "Drive native sound and Box3D effects from the latest Flecs event."
  :-
  :void
  []
  (let [state (factory/snapshot)
        current-count (az/field state event_count)]
    (when (> current-count handled-event-count)
      (let [event (factory/latest-event)
            kind (az/field event kind)]
        (when (or (ak/== kind 1)
                  (ak/== kind 2)
                  (ak/== kind 4))
          (audio/play-event! kind)
          (physics/emit! (az/field event x) (az/field event y) kind))
        (set! handled-event-count current-count)))))

(az/defn tick
  "Advance one native Flecs/factory frame and consume one pointer edge."
  :-
  RenderPacket
  [[pointer-x :f32]
   [pointer-y :f32]
   [viewport-width :f32]
   [viewport-height :f32]
   [delta-seconds :f32]
   [pointer-down :bool]
   [pointer-pressed :bool]]
  (set! _ viewport-width)
  (set! _ viewport-height)
  (set! _ (initialize!))
  (lock-state!)
  (let [^{:var true}
        work-clock (flecs/ecs_time_t {:sec 0 :nanosec 0})]
    (set! _ (flecs/ecs_time_measure (ak/& work-clock)))
    (set! _ (factory/handle-pointer! pointer-x pointer-y
                                     (or pointer-pressed pointer-down)))
    (let [flecs-world (az/unwrap world)
          before-info (flecs/ecs_get_world_info flecs-world)
          before-frame-time (az/field (az/deref before-info) frame_time_total)]
      (when (ak/== (az/field (ak/import "builtin") mode) :.Debug)
        (factory/refresh-system-callback!))
      (let [pre-work (flecs/ecs_time_measure (ak/& work-clock))]
        (set! _ (flecs/ecs_progress flecs-world delta-seconds))
        (let [frame-info (flecs/ecs_get_world_info flecs-world)
              frame-work
              (ak/max
               0.0
               (ak/as :f64
                      (ak/floatCast
                       (- (az/field (az/deref frame-info) frame_time_total)
                          before-frame-time))))]
          (set! _ (flecs/ecs_time_measure (ak/& work-clock)))
          (factory/step! (az/field (az/deref frame-info) delta_time))
          (process-factory-event!)
          (physics/step! (az/field (az/deref frame-info) delta_time))
          (let [post-work (flecs/ecs_time_measure (ak/& work-clock))]
            (set! last-frame-work-seconds-value
                  (+ (+ pre-work frame-work) post-work))))))
    (set! frame-count (+ frame-count 1))
    (let [result (RenderPacket {:frame frame-count})]
      (unlock-state!)
      result)))

(az/defn tick-auto
  "Run one Flecs-paced frame for the native platform loop."
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

(az/defn toggle-paused!
  "Pause or resume the actual factory simulation."
  :-
  :bool
  []
  (factory/toggle-paused!))

(az/defn restart!
  "Recreate the native world only when the player explicitly requests reset."
  :-
  :void
  []
  (shutdown)
  (set! _ (initialize!)))

(az/defn snapshot
  "Inspect the live Flecs owner and objective without advancing either."
  :-
  WorldSnapshot
  []
  (lock-state!)
  (let [factory-state (factory/snapshot)
        fps (if (ak/== world null)
              0.0
              (let [info (flecs/ecs_get_world_info world)
                    delta-time (az/field (az/deref info) delta_time_raw)]
                (if (> delta-time 0.0) (/ 1.0 delta-time) 0.0)))
        result
        (WorldSnapshot
         {:initialized (ak/!= world null)
          :frames frame-count
          :fps fps
          :buildings (az/field factory-state buildings)
          :houses_completed (az/field factory-state houses_completed)
          :world_address (if (ak/== world null)
                           0
                           (ak/intFromPtr (az/unwrap world)))})]
    (unlock-state!)
    result))

(az/defn shutdown
  "Destroy the native world. Ordinary source edits never call this."
  :-
  :void
  []
  (when (ak/!= world null)
    (physics/shutdown!)
    (factory/shutdown!)
    (set! _ (flecs/ecs_fini world))
    (set! world null))
  (set! frame-count 0)
  (set! handled-event-count 0)
  (set! last-frame-work-seconds-value 0.0))
