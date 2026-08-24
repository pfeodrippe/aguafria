(ns racing-game.render
  "Allocation-free top-down race geometry for the shared Vulkan stream."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [aguafria-examples-native.mesh :as mesh]
            [racing-game.simulation :as simulation]
            [racing-game.track :as track]))

(az/defconst circle-segments :usize 18)

(az/defconst track-segments :usize 128)

(az/defvar debug-overlay-visible true)

(az/defn set-debug-overlay!
  "Show or hide native racer intent and cognition geometry at runtime."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[visible :bool]]
  (do
    (set! debug-overlay-visible visible)
    debug-overlay-visible))

(az/defn toggle-debug-overlay!
  "Toggle the allocation-free cognition overlay without restarting the race."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (set-debug-overlay! (ak/! debug-overlay-visible)))

(az/defn write-vertex!
  {:export false}
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
  {:export false}
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
  {:export false}
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
  {:export false}
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

(az/defn append-circle!
  "Append only a circular contour; the interior remains the black field."
  {:export false}
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
               (+ center-x (* (std-math/cos angle-a) radius))
               (+ center-y (* (std-math/sin angle-a) radius))
               (+ center-x (* (std-math/cos angle-b) radius))
               (+ center-y (* (std-math/sin angle-b) radius))
               0.0035 z r g b))))
    next))

(az/defn append-track!
  {:export false}
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
              (append-line! output next
                            (az/field outer-a x) (az/field outer-a y)
                            (az/field outer-b x) (az/field outer-b y)
                            0.0045 0.69 1.0 0.78 0.0))
        (set! next
              (append-line! output next
                            (az/field inner-a x) (az/field inner-a y)
                            (az/field inner-b x) (az/field inner-b y)
                            0.0045 0.69 1.0 0.78 0.0))
        (when (ak/== (mod segment 4) 0)
          (set! next
                (append-line! output next
                              (az/field center-a x) (az/field center-a y)
                              (az/field center-b x) (az/field center-b y)
                              0.0020 0.70 0.55 0.43 0.0)))))
    next))

(az/defn append-pickups!
  {:export false}
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
        (set! next (append-line! output next x (+ y radius) (+ x radius) y
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-line! output next (+ x radius) y x (- y radius)
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-line! output next x (- y radius) (- x radius) y
                                 0.003 0.50 1.0 0.78 0.0))
        (set! next (append-line! output next (- x radius) y x (+ y radius)
                                 0.003 0.50 1.0 0.78 0.0))))
    next))

(az/defn append-hazards!
  "Render pooled bolts as arrow diamonds and traps as crossed contours."
  {:export false}
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
                (set! next (append-line! output next x (+ y radius)
                                         (+ x radius) y
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-line! output next (+ x radius) y
                                         x (- y radius)
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-line! output next x (- y radius)
                                         (- x radius) y
                                         0.003 0.38 1.0 0.78 0.0))
                (set! next (append-line! output next (- x radius) y
                                         x (+ y radius)
                                         0.003 0.38 1.0 0.78 0.0)))
              (do
                (set! next (append-line! output next
                                         (- x radius) (- y radius)
                                         (+ x radius) (+ y radius)
                                         0.004 0.38 1.0 0.78 0.0))
                (set! next (append-line! output next
                                         (- x radius) (+ y radius)
                                         (+ x radius) (- y radius)
                                         0.004 0.38 1.0 0.78 0.0))))))))
    next))

(az/defn append-racers!
  {:export false}
  :-
  :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [count :usize]]
  (let [^{:var true :zig/type :usize} next count]
    (dotimes [index simulation/racer-count]
      (let [view (simulation/racer-view (ak/intCast index))
            identifier (az/field view id)
            x (az/field view x)
            y (az/field view y)
            heading (az/field view heading)
            ^{:zig/type :f32}
            radius (if (az/field view shielded) 0.035 0.028)]
        (when (az/field view shielded)
          (set! next (append-circle! output next x y 0.043 0.40 0.55 0.43 0.0)))
        (set! next (append-circle! output next x y radius 0.35 1.0 0.78 0.0))
        (when (ak/== (mod identifier 2) 0)
          (set! next (append-circle! output next x y 0.015 0.34 0.80 0.62 0.0)))
        (set! next
              (append-line! output next x y
                            (+ x (* (std-math/cos heading)
                                    (+ 0.046 (* (ak/as :f32 (ak/floatFromInt identifier)) 0.002))))
                            (+ y (* (std-math/sin heading)
                                    (+ 0.046 (* (ak/as :f32 (ak/floatFromInt identifier)) 0.002))))
                            0.004 0.31 1.0 0.78 0.0))))
    next))

(az/defn append-ranking!
  "Show authoritative first-through-eighth classification on the right."
  {:export false}
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
  {:export false}
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
              (append-line! output next
                            (az/field view x) (az/field view y)
                            (az/field lane-goal x) (az/field lane-goal y)
                            0.0018 0.30 0.78 0.60 0.0))
        (when (and (< target-id simulation/racer-count)
                   (ak/!= target-id (az/field view id)))
          (let [target (simulation/racer-view target-id)]
            (set! next
                  (append-line! output next
                                (az/field view x) (az/field view y)
                                (az/field target x) (az/field target y)
                                0.00065 0.29 0.34 0.26 0.0))))))
    next))

(az/defn append-cognition-overlay!
  "Draw eight native actor rows. The upper bar is desired speed, the lower bar
  is average inference latency (full width at 600 ms), the left ring is the
  actor, and a second ring means a request is currently in flight."
  {:export false}
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
            source-brightness (if (ak/== (az/field view source) 1) 1.0 0.48)]
        (set! next
              (append-circle! output next -0.935 y 0.018 0.24
                              source-brightness
                              (* source-brightness 0.78) 0.0))
        (when (az/field view pending)
          (set! next
                (append-circle! output next -0.935 y 0.025 0.23
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
  {:attrs #{:public :export :implicit-return}}
  :-
  :u32
  [[output [:c-pointer mesh/GpuVertex]]
   [frame-width :i32]
   [frame-height :i32]]
  (set! _ frame-width)
  (set! _ frame-height)
  (let [track-count (append-track! output 0)
        pickup-count (append-pickups! output track-count)
        hazard-count (append-hazards! output pickup-count)
        racer-vertices (append-racers! output hazard-count)
        rank-vertices (append-ranking! output racer-vertices)
        intent-vertices
        (if debug-overlay-visible
          (append-intent-lines! output rank-vertices)
          rank-vertices)
        final-count
        (if debug-overlay-visible
          (append-cognition-overlay! output intent-vertices)
          intent-vertices)]
    (ak/as :u32 (ak/intCast final-count))))
