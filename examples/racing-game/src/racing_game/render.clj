(ns racing-game.render
  "Allocation-free top-down race geometry for the shared Vulkan stream."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [aguafria-examples-native.mesh :as mesh]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]
            [racing-game.track :as track]))

(az/defconst circle-segments :usize 18)

(az/defconst track-segments :usize 128)

(az/defstruct WorldScale
  "Aspect-fit scale used by world geometry while screen-space HUD stays fixed."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]])

(az/defstruct RacerColor
  "Stable high-contrast outline color assigned to one racer identity."
  {:layout :extern}
  [[:r :f32]
   [:g :f32]
   [:b :f32]])

(az/defvar debug-overlay-visible false)

(az/defvar world-x-scale :f32 1.0)

(az/defvar world-y-scale :f32 1.0)

(az/defn- racer-color
  "Return the permanent display color for one of the eight racers."
  :-
  RacerColor
  [[identifier :u8]]
  (cond
    (ak/== identifier 0) (RacerColor {:r 0.05 :g 0.82 :b 1.00})
    (ak/== identifier 1) (RacerColor {:r 1.00 :g 0.25 :b 0.66})
    (ak/== identifier 2) (RacerColor {:r 0.38 :g 1.00 :b 0.28})
    (ak/== identifier 3) (RacerColor {:r 1.00 :g 0.42 :b 0.08})
    (ak/== identifier 4) (RacerColor {:r 0.62 :g 0.38 :b 1.00})
    (ak/== identifier 5) (RacerColor {:r 1.00 :g 0.90 :b 0.22})
    (ak/== identifier 6) (RacerColor {:r 0.18 :g 1.00 :b 0.72})
    :else (RacerColor {:r 1.00 :g 0.30 :b 0.30})))

(az/defn set-debug-overlay!
  "Show or hide native racer intent and cognition geometry at runtime."
  :-
  :bool
  [[visible :bool]]
  (do
    (set! debug-overlay-visible visible)
    debug-overlay-visible))

(az/defn toggle-debug-overlay!
  "Toggle the allocation-free cognition overlay without restarting the race."
  :-
  :bool
  []
  (set-debug-overlay! (ak/! debug-overlay-visible)))

(az/defn configure-world-scale!
  "Fit equal world units to equal framebuffer pixels for any aspect ratio."
  :-
  WorldScale
  [[frame-width :i32]
   [frame-height :i32]]
  (let [width (ak/as :f32 (ak/floatFromInt (ak/max frame-width 1)))
        height (ak/as :f32 (ak/floatFromInt (ak/max frame-height 1)))]
    (set! world-x-scale (if (> width height) (/ height width) 1.0))
    (set! world-y-scale (if (> height width) (/ width height) 1.0))
    (WorldScale {:x world-x-scale :y world-y-scale})))

(az/defn write-vertex!
  :-
  :void
  [[output [:c-pointer mesh/GpuVertex]]
   [index :usize]
   [x :f32]
   [y :f32]
   [z :f32]
   [r :f32]
   [g :f32]
   [b :f32]]
  (set! (az/index output index)
        (mesh/GpuVertex {:x x :y y :z z :r r :g g :b b})))

(az/defn append-triangle!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [ax :f32] [ay :f32]
   [bx :f32] [by :f32]
   [cx :f32] [cy :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (if (> (+ count 3) mesh/frame-capacity)
    count
    (do
      (write-vertex! output count ax ay z r g b)
      (write-vertex! output (+ count 1) bx by z r g b)
      (write-vertex! output (+ count 2) cx cy z r g b)
      (+ count 3))))

(az/defn append-quad!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [ax :f32] [ay :f32]
   [bx :f32] [by :f32]
   [cx :f32] [cy :f32]
   [dx :f32] [dy :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (let [after-first (append-triangle! output count ax ay bx by cx cy z r g b)]
    (append-triangle! output after-first ax ay cx cy dx dy z r g b)))

(az/defn append-line!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [ax :f32] [ay :f32]
   [bx :f32] [by :f32]
   [width :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (let [dx (- bx ax)
        dy (- by ay)
        length (std-math/sqrt (+ (* dx dx) (* dy dy)))
        safe-length (ak/max length 0.00001)
        nx (* (/ (- dy) safe-length) width)
        ny (* (/ dx safe-length) width)]
    (append-quad! output count
                  (+ ax nx) (+ ay ny)
                  (+ bx nx) (+ by ny)
                  (- bx nx) (- by ny)
                  (- ax nx) (- ay ny)
                  z r g b)))

(az/defn append-world-line!
  "Draw a world-space line after fitting equal logical X/Y units to equal
  framebuffer pixels. UI geometry intentionally continues to use raw NDC."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [ax :f32] [ay :f32]
   [bx :f32] [by :f32]
   [width :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (let [dx (- bx ax)
        dy (- by ay)
        length (std-math/sqrt (+ (* dx dx) (* dy dy)))
        safe-length (ak/max length 0.00001)
        nx (* (/ (- dy) safe-length) width)
        ny (* (/ dx safe-length) width)]
    (append-quad! output count
                  (* (+ ax nx) world-x-scale) (* (+ ay ny) world-y-scale)
                  (* (+ bx nx) world-x-scale) (* (+ by ny) world-y-scale)
                  (* (- bx nx) world-x-scale) (* (- by ny) world-y-scale)
                  (* (- ax nx) world-x-scale) (* (- ay ny) world-y-scale)
                  z r g b)))

(az/defn append-circle!
  "Append only a circular contour; the interior remains the black field."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [center-x :f32]
   [center-y :f32]
   [radius :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [segment circle-segments]
      (let [angle-a (* (/ (ak/as :f32 (ak/floatFromInt segment))
                           (ak/as :f32 (ak/floatFromInt circle-segments)))
                        6.2831855)
            angle-b (* (/ (ak/as :f32 (ak/floatFromInt (+ segment 1)))
                           (ak/as :f32 (ak/floatFromInt circle-segments)))
                        6.2831855)]
        (set! next
              (append-world-line!
               output next
               (+ center-x (* (std-math/cos angle-a) radius))
               (+ center-y (* (std-math/sin angle-a) radius))
               (+ center-x (* (std-math/cos angle-b) radius))
               (+ center-y (* (std-math/sin angle-b) radius))
               0.0035 z r g b))))
    next))

(az/defn append-screen-circle!
  "Append a pixel-circular HUD contour around an unscaled NDC center."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]
   [center-x :f32]
   [center-y :f32]
   [radius :f32]
   [z :f32]
   [r :f32] [g :f32] [b :f32]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [segment circle-segments]
      (let [angle-a (* (/ (ak/as :f32 (ak/floatFromInt segment))
                           (ak/as :f32 (ak/floatFromInt circle-segments)))
                        6.2831855)
            angle-b (* (/ (ak/as :f32 (ak/floatFromInt (+ segment 1)))
                           (ak/as :f32 (ak/floatFromInt circle-segments)))
                        6.2831855)]
        (set! next
              (append-line!
               output next
               (+ center-x (* (std-math/cos angle-a) radius world-x-scale))
               (+ center-y (* (std-math/sin angle-a) radius world-y-scale))
               (+ center-x (* (std-math/cos angle-b) radius world-x-scale))
               (+ center-y (* (std-math/sin angle-b) radius world-y-scale))
               0.0035 z r g b))))
    next))

(az/defn append-race-state!
  "Draw three compact start lights in screen space while the deterministic
  countdown is active. They disappear on the exact tick racing begins."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [race (simulation/snapshot)
        ^{:var true :zig/type :usize} next count]
    (when (ak/== (az/field race state) simulation/race-state-countdown)
      (let [remaining
            (ak/divTrunc (+ (az/field race countdown_ticks) 119) 120)]
        (dotimes [slot 3]
          (let [active (< slot remaining)
                ^{:zig/type :f32}
                brightness (if active 1.0 0.28)]
            (set! next
                  (append-screen-circle!
                   output next
                   (+ -0.08 (* (ak/as :f32 (ak/floatFromInt slot)) 0.08))
                   0.86 0.026 0.20 brightness (* brightness 0.78) 0.0))))))
    next))

(az/defn append-track!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [segment track-segments]
      (let [progress-a (/ (ak/as :f32 (ak/floatFromInt segment))
                          (ak/as :f32 (ak/floatFromInt track-segments)))
            progress-b (/ (ak/as :f32 (ak/floatFromInt (+ segment 1)))
                          (ak/as :f32 (ak/floatFromInt track-segments)))
            outer-a (track/pose progress-a 0.13)
            outer-b (track/pose progress-b 0.13)
            inner-a (track/pose progress-a -0.13)
            inner-b (track/pose progress-b -0.13)
            center-a (track/pose progress-a 0.0)
            center-b (track/pose progress-b 0.0)]
        (set! next
              (append-world-line! output next
                            (az/field outer-a x) (az/field outer-a y)
                            (az/field outer-b x) (az/field outer-b y)
                            0.0045 0.69 1.0 0.78 0.0))
        (set! next
              (append-world-line! output next
                            (az/field inner-a x) (az/field inner-a y)
                            (az/field inner-b x) (az/field inner-b y)
                            0.0045 0.69 1.0 0.78 0.0))
        (when (ak/== (mod segment 4) 0)
          (set! next
                (append-world-line! output next
                              (az/field center-a x) (az/field center-a y)
                              (az/field center-b x) (az/field center-b y)
                              0.0020 0.70 0.55 0.43 0.0)))))
    next))

(az/defn append-pits!
  "Draw the shared pit lane and four real team boxes beside the final sector."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [segment 18]
      (let [progress-a (+ 0.80
                          (* (/ (ak/as :f32 (ak/floatFromInt segment)) 18.0)
                             0.18))
            progress-b (+ 0.80
                          (* (/ (ak/as :f32
                                      (ak/floatFromInt (+ segment 1))) 18.0)
                             0.18))
            a (track/pose progress-a 0.19)
            b (track/pose progress-b 0.19)]
        (set! next
              (append-world-line! output next
                                  (az/field a x) (az/field a y)
                                  (az/field b x) (az/field b y)
                                  0.0025 0.61 0.72 0.56 0.0))))
    (let [entry-track (track/pose 0.80 0.13)
          entry-pit (track/pose 0.80 0.19)
          exit-track (track/pose 0.98 0.13)
          exit-pit (track/pose 0.98 0.19)]
      (set! next
            (append-world-line! output next
                                (az/field entry-track x) (az/field entry-track y)
                                (az/field entry-pit x) (az/field entry-pit y)
                                0.0025 0.61 0.72 0.56 0.0))
      (set! next
            (append-world-line! output next
                                (az/field exit-pit x) (az/field exit-pit y)
                                (az/field exit-track x) (az/field exit-track y)
                                0.0025 0.61 0.72 0.56 0.0)))
    (dotimes [team-index simulation/team-count]
      (let [progress (simulation/pit-box-progress (ak/intCast team-index))
            team (simulation/team-view (ak/intCast team-index))
            box (track/pose progress 0.215)
            heading (az/field box heading)
            tx (std-math/cos heading)
            ty (std-math/sin heading)
            nx (- ty)
            ny tx
            half-length 0.014
            half-width 0.015
            ax (+ (az/field box x) (* tx half-length) (* nx half-width))
            ay (+ (az/field box y) (* ty half-length) (* ny half-width))
            bx (+ (az/field box x) (* tx half-length) (* nx (- half-width)))
            by (+ (az/field box y) (* ty half-length) (* ny (- half-width)))
            cx (+ (az/field box x) (* tx (- half-length)) (* nx (- half-width)))
            cy (+ (az/field box y) (* ty (- half-length)) (* ny (- half-width)))
            dx (+ (az/field box x) (* tx (- half-length)) (* nx half-width))
            dy (+ (az/field box y) (* ty (- half-length)) (* ny half-width))
            occupied (ak/!= (az/field team pit_occupant)
                             simulation/no-pit-occupant)
            brightness (if occupied
                         (ak/as :f32 1.0)
                         (ak/as :f32 0.55))]
        (set! next (append-world-line! output next ax ay bx by 0.003 0.64
                                       brightness (* brightness 0.78) 0.0))
        (set! next (append-world-line! output next bx by cx cy 0.003 0.64
                                       brightness (* brightness 0.78) 0.0))
        (set! next (append-world-line! output next cx cy dx dy 0.003 0.64
                                       brightness (* brightness 0.78) 0.0))
        (set! next (append-world-line! output next dx dy ax ay 0.003 0.64
                                       brightness (* brightness 0.78) 0.0))))
    next))

(az/defn append-pickups!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [slot 4]
      (let [sample (track/pose (* (ak/as :f32 (ak/floatFromInt slot)) 0.25) 0.0)
            x (az/field sample x)
            y (az/field sample y)
            radius 0.025]
        (set! next (append-world-line! output next x (+ y radius) (+ x radius) y
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-world-line! output next (+ x radius) y x (- y radius)
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-world-line! output next x (- y radius) (- x radius) y
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-world-line! output next (- x radius) y x (+ y radius)
                                 0.003 0.50 1.0 0.78 0.0))))
    next))

(az/defn append-hazards!
  "Render pooled bolts as arrow diamonds and traps as crossed contours."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [slot simulation/hazard-capacity]
      (let [hazard (simulation/hazard-view slot)]
        (when (az/field hazard active)
          (let [x (az/field hazard x)
                y (az/field hazard y)
                ^{:zig/type :f32}
                radius (if (ak/== (az/field hazard kind)
                                  simulation/item-bolt)
                         0.018
                         0.022)]
            (if (ak/== (az/field hazard kind) simulation/item-bolt)
              (do
                (set! next (append-world-line! output next x (+ y radius)
                                         (+ x radius) y
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-world-line! output next (+ x radius) y
                                         x (- y radius)
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-world-line! output next x (- y radius)
                                         (- x radius) y
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-world-line! output next (- x radius) y
                                         x (+ y radius)
                                         0.003 0.38 1.0 0.78 0.0)))
              (do
                (set! next (append-world-line! output next
                                         (- x radius) (- y radius)
                                         (+ x radius) (+ y radius)
                                         0.004 0.38 1.0 0.78 0.0))
                (set! next (append-world-line! output next
                                         (- x radius) (+ y radius)
                                         (+ x radius) (- y radius)
                                         0.004 0.38 1.0 0.78 0.0))))))))
    next))

(az/defn append-racers!
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [human (simulation/human-control-snapshot)
        ^{:var true :zig/type :usize} next count]
    (dotimes [index simulation/racer-count]
      (let [view (simulation/racer-view (ak/intCast index))
            identifier (az/field view id)
            color (racer-color identifier)
            finished (az/field view finished)
            parking-index (- (az/field view rank) 1)
            parking-column (mod parking-index 4)
            parking-row (ak/divTrunc parking-index 4)
            ^{:zig/type :f32}
            x (if finished
                (+ -0.24
                   (* (ak/as :f32 (ak/floatFromInt parking-column)) 0.16))
                (az/field view x))
            ^{:zig/type :f32}
            y (if finished
                (- -0.06
                   (* (ak/as :f32 (ak/floatFromInt parking-row)) 0.11))
                (az/field view y))
            ^{:zig/type :f32}
            heading (if finished 0.0 (az/field view heading))
            ^{:zig/type :f32}
            radius (if (az/field view shielded) 0.035 0.028)]
        (when (az/field view shielded)
          (set! next
                (append-circle! output next x y 0.043 0.40
                                (* (az/field color r) 0.55)
                                (* (az/field color g) 0.55)
                                (* (az/field color b) 0.55))))
        (when (and (az/field human enabled) (ak/== identifier 0))
          (set! next (append-circle! output next x y 0.050 0.39 1.0 1.0 1.0)))
        (set! next
              (append-circle! output next x y radius 0.35
                              (az/field color r)
                              (az/field color g)
                              (az/field color b)))
        (when (ak/== (mod identifier 2) 0)
          (set! next
                (append-circle! output next x y 0.015 0.34
                                (* (az/field color r) 0.80)
                                (* (az/field color g) 0.80)
                                (* (az/field color b) 0.80))))
        (set! next
              (append-world-line! output next x y
                            (+ x (* (std-math/cos heading)
                                    (+ 0.046 (* (ak/as :f32 (ak/floatFromInt identifier)) 0.002))))
                            (+ y (* (std-math/sin heading)
                                    (+ 0.046 (* (ak/as :f32 (ak/floatFromInt identifier)) 0.002))))
                            0.004 0.31
                            (az/field color r)
                            (az/field color g)
                            (az/field color b)))))
    next))

(az/defn append-ranking!
  "Show authoritative first-through-eighth classification on the right."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [rank-index simulation/racer-count]
      (dotimes [racer-index simulation/racer-count]
        (let [view (simulation/racer-view (ak/intCast racer-index))]
          (when (ak/== (az/field view rank) (+ rank-index 1))
            (let [y (- 0.82 (* (ak/as :f32 (ak/floatFromInt rank-index)) 0.105))
                  length (+ 0.055 (* (az/field view progress) 0.11))]
              (set! next (append-line! output next 0.80 (+ y 0.030)
                                       (+ 0.80 length) (+ y 0.030)
                                       0.0025 0.25 1.0 0.78 0.0))
              (set! next (append-line! output next (+ 0.80 length) (+ y 0.030)
                                       (+ 0.80 length) (- y 0.030)
                                       0.0025 0.25 1.0 0.78 0.0))
              (set! next (append-line! output next (+ 0.80 length) (- y 0.030)
                                       0.80 (- y 0.030)
                                       0.0025 0.25 1.0 0.78 0.0))
              (set! next (append-line! output next 0.80 (- y 0.030)
                                       0.80 (+ y 0.030)
                                       0.0025 0.25 1.0 0.78 0.0)))))))
    next))

(az/defn append-intent-lines!
  "Draw each racer's chosen target and short-horizon lane goal."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [index simulation/racer-count]
      (let [view (simulation/racer-view (ak/intCast index))
            target-id (az/field view target)
            lane-goal
            (track/pose
             (mod (+ (az/field view progress) 0.045) 1.0)
             (az/field view lane_target))]
        (set! next
              (append-world-line! output next
                            (az/field view x) (az/field view y)
                            (az/field lane-goal x) (az/field lane-goal y)
                            0.0018 0.30 0.78 0.60 0.0))
        (when (and (< target-id simulation/racer-count)
                   (ak/!= target-id (az/field view id)))
          (let [target (simulation/racer-view target-id)]
            (set! next
                  (append-world-line! output next
                                (az/field view x) (az/field view y)
                                (az/field target x) (az/field target y)
                                0.00065 0.29 0.34 0.26 0.0))))))
    next))

(az/defn append-cognition-overlay!
  "Draw eight native actor rows. The upper bar is desired speed, the lower bar
  is average inference latency (full width at 600 ms), the left ring is the
  actor, and a second ring means a request is currently in flight."
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [index simulation/racer-count]
      (let [view (simulation/racer-view (ak/intCast index))
            y (- 0.84 (* (ak/as :f32 (ak/floatFromInt index)) 0.105))
            speed-width
            (* (ak/min 1.0
                       (/ (ak/max 0.0 (- (az/field view target_speed) 0.04))
                          0.08))
               0.17)
            latency-width
            (* (ak/min 1.0
                       (/ (ak/as :f32
                                 (ak/floatFromInt
                                  (az/field view average_latency_us)))
                          600000.0))
               0.17)
            ^{:zig/type :f32}
            source-brightness
            (cond
              (ak/== (az/field view source) telemetry/source-llm)
              (ak/as :f32 1.0)

              (ak/== (az/field view source) telemetry/source-human)
              (ak/as :f32 0.82)

              :else
              (ak/as :f32 0.48))]
        (set! next
              (append-screen-circle! output next -0.935 y 0.018 0.24
                              source-brightness
                              (* source-brightness 0.78) 0.0))
        (when (az/field view pending)
          (set! next
                (append-screen-circle! output next -0.935 y 0.025 0.23
                                1.0 0.78 0.0)))
        (set! next
              (append-line! output next -0.895 (+ y 0.010)
                            (+ -0.895 speed-width) (+ y 0.010)
                            0.003 0.23 1.0 0.78 0.0))
        (set! next
              (append-line! output next -0.895 (- y 0.010)
                            (+ -0.895 latency-width) (- y 0.010)
                            0.002 0.23 0.56 0.44 0.0))
        (when (ak/== (az/field view item_action) simulation/action-use)
          (set! next
                (append-line! output next -0.705 (- y 0.017)
                              -0.685 (+ y 0.017)
                              0.003 0.23 1.0 0.78 0.0)))))
    next))

(az/defn build-frame!
  "Build the complete track, item, racer, intent, and rank view natively."
  {:attrs #{:export}}
  :-
  :u32
  [[output [:c-pointer mesh/GpuVertex]]
   [frame-width :i32]
   [frame-height :i32]]
  (set! _ (configure-world-scale! frame-width frame-height))
  (let [track-count (append-track! output 0)
        pit-count (append-pits! output track-count)
        pickup-count (append-pickups! output pit-count)
        hazard-count (append-hazards! output pickup-count)
        racer-vertices (append-racers! output hazard-count)
        intent-vertices
        (if debug-overlay-visible
          (append-intent-lines! output racer-vertices)
          racer-vertices)
        state-count (append-race-state! output intent-vertices)]
    (ak/as :u32 (ak/intCast state-count))))
