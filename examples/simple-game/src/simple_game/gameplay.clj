(ns simple-game.gameplay
  "Deterministic, hot-reloadable game rules shared by desktop and WebAssembly."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct GameplaySnapshot
  "Complete, inspectable state for the small target game."
  {:layout :extern}
  [[:initialized :bool]
   [:paused :bool]
   [:game_over :bool]
   [:score :u32]
   [:combo :u32]
   [:best_combo :u32]
   [:lives :u8]
   [:level :u8]
   [:hits :u32]
   [:misses :u32]
   [:time_remaining :f32]
   [:target_x :f32]
   [:target_y :f32]
   [:target_radius :f32]
   [:velocity_x :f32]
   [:velocity_y :f32]
   [:seed :u32]])

(az/defconst initial-time :f32 30.0)

(az/defconst initial-lives :u8 3)

(az/defvar initialized false)

(az/defvar paused false)

(az/defvar game-over false)

(az/defvar score :u32 0)

(az/defvar combo :u32 0)

(az/defvar best-combo :u32 0)

(az/defvar lives :u8 initial-lives)

(az/defvar level :u8 1)

(az/defvar hits :u32 0)

(az/defvar misses :u32 0)

(az/defvar time-remaining :f32 initial-time)

(az/defvar target-x :f32 360.0)

(az/defvar target-y :f32 270.0)

(az/defvar target-radius :f32 90.0)

(az/defvar velocity-x :f32 54.0)

(az/defvar velocity-y :f32 37.0)

(az/defvar random-seed :u32 0xA6F12D37)

(az/defn score-for-hit
  "Pure score rule, kept separate so it is cheap to test and live-edit."
  :-
  :u32
  [[current-combo :u32]
   [current-level :u8]]
  (+ 100
     (* current-combo 25)
     (* (ak/as :u32 current-level) 50)))

(az/defn level-for-hits
  "Increase difficulty after every five successful hits."
  :-
  :u8
  [[hit-count :u32]]
  (ak/as :u8 (ak/intCast (+ 1 (ak/divTrunc hit-count 5)))))

(az/defn radius-for-level
  "Shrink the target with a floor that remains comfortably clickable."
  :-
  :f32
  [[current-level :u8]]
  (ak/max
   42.0
   (- 90.0 (* 4.0 (ak/as :f32 (ak/floatFromInt current-level))))))

(az/defn next-random!
  "Advance a compact deterministic generator without allocation."
  {:export false}
  :-
  :u32
  []
  (set! random-seed
        (ak/+% (ak/*% random-seed 1664525) 1013904223))
  random-seed)

(az/defn random-unit!
  {:export false}
  :-
  :f32
  []
  (/ (ak/as :f32 (ak/floatFromInt (mod (next-random!) 65536)))
     65535.0))

(az/defn reset!
  "Start a deterministic session. The best combo survives ordinary restarts."
  :-
  :void
  [[seed :u32]]
  (set! initialized true)
  (set! paused false)
  (set! game-over false)
  (set! score 0)
  (set! combo 0)
  (set! lives initial-lives)
  (set! level 1)
  (set! hits 0)
  (set! misses 0)
  (set! time-remaining initial-time)
  (set! target-x 360.0)
  (set! target-y 270.0)
  (set! target-radius (radius-for-level level))
  (set! velocity-x 54.0)
  (set! velocity-y 37.0)
  (set! random-seed (if (ak/== seed 0) 0xA6F12D37 seed)))

(az/defn initialize!
  "Initialize once so repeated nREPL evaluation preserves the live session."
  :-
  :bool
  []
  (when (ak/! initialized)
    (reset! random-seed))
  initialized)

(az/defn restart!
  :-
  :void
  []
  (reset! random-seed))

(az/defn set-paused!
  :-
  :void
  [[value :bool]]
  (when (ak/! game-over)
    (set! paused value)))

(az/defn toggle-paused!
  :-
  :bool
  []
  (set-paused! (ak/! paused))
  paused)

(az/defn set-target!
  "Inspector-safe target edit in design-space pixels."
  :-
  :void
  [[x :f32]
   [y :f32]
   [radius :f32]]
  (set! target-x (ak/max 0.0 (ak/min 720.0 x)))
  (set! target-y (ak/max 0.0 (ak/min 540.0 y)))
  (set! target-radius (ak/max 12.0 (ak/min 180.0 radius))))

(az/defn register-hit!
  "Apply one successful activation and relocate the target deterministically."
  :-
  :u32
  []
  (set! _ (initialize!))
  (when (and (ak/! paused) (ak/! game-over))
    (set! hits (+ hits 1))
    (set! combo (+ combo 1))
    (set! best-combo (ak/max best-combo combo))
    (set! level (level-for-hits hits))
    (set! score (+ score (score-for-hit combo level)))
    (set! time-remaining (ak/min 45.0 (+ time-remaining 0.75)))
    (set! target-radius (radius-for-level level))
    (set! target-x (+ 100.0 (* (random-unit!) 520.0)))
    (set! target-y (+ 125.0 (* (random-unit!) 285.0)))
    (let [speed (+ 52.0 (* 8.0 (ak/as :f32 (ak/floatFromInt level))))]
      (set! velocity-x (if (< (random-unit!) 0.5) (- 0.0 speed) speed))
      (set! velocity-y
            (if (< (random-unit!) 0.5)
              (- 0.0 (* speed 0.72))
              (* speed 0.72)))))
  score)

(az/defn register-miss!
  "Lose one life for an activation outside the target."
  :-
  :u8
  []
  (set! _ (initialize!))
  (when (and (ak/! paused) (ak/! game-over))
    (set! misses (+ misses 1))
    (set! combo 0)
    (when (> lives 0)
      (set! lives (- lives 1)))
    (when (ak/== lives 0)
      (set! game-over true)))
  lives)

(az/defn step!
  "Advance timer and target motion in stable design coordinates."
  :-
  :void
  [[delta-seconds :f32]]
  (set! _ (initialize!))
  (when (and (ak/! paused) (ak/! game-over))
    (let [step (ak/min 0.05 (ak/max 0.0 delta-seconds))]
      (set! time-remaining (ak/max 0.0 (- time-remaining step)))
      (when (<= time-remaining 0.0)
        (set! game-over true))
      (set! target-x (+ target-x (* velocity-x step)))
      (set! target-y (+ target-y (* velocity-y step)))
      (when (or (< (- target-x target-radius) 24.0)
                (> (+ target-x target-radius) 696.0))
        (set! velocity-x (- 0.0 velocity-x))
        (set! target-x (ak/max (+ 24.0 target-radius)
                               (ak/min (- 696.0 target-radius) target-x))))
      (when (or (< (- target-y target-radius) 92.0)
                (> (+ target-y target-radius) 492.0))
        (set! velocity-y (- 0.0 velocity-y))
        (set! target-y (ak/max (+ 92.0 target-radius)
                               (ak/min (- 492.0 target-radius) target-y)))))))

(az/defn state-hash
  "Stable checksum for recording/replay verification."
  :-
  :u64
  []
  (let [base (ak/as :u64 score)]
    (ak/+%
     (ak/*% base 1099511628211)
     (+ (ak/as :u64 hits)
        (* (ak/as :u64 misses) 257)
        (* (ak/as :u64 lives) 65537)
        (* (ak/as :u64 level) 16777619)))))

(az/defn snapshot
  "Return exact native gameplay state for rendering or nREPL inspection."
  :-
  GameplaySnapshot
  []
  (GameplaySnapshot
   {:initialized initialized
    :paused paused
    :game_over game-over
    :score score
    :combo combo
    :best_combo best-combo
    :lives lives
    :level level
    :hits hits
    :misses misses
    :time_remaining time-remaining
    :target_x target-x
    :target_y target-y
    :target_radius target-radius
    :velocity_x velocity-x
    :velocity_y velocity-y
    :seed random-seed}))
