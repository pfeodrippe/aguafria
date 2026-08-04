(ns simple-game.scene
  "Renderer-independent scene construction shared by desktop and WebAssembly."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [simple-game.animation :as animation]
            [simple-game.font :as font]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.physics :as physics]))

(az/defstruct Color
  "Linear RGBA color consumed by every graphics backend."
  {:layout :extern}
  [[:r :f32]
   [:g :f32]
   [:b :f32]
   [:a :f32]])

(az/defconst DrawRect
  {:attrs #{:public}}
  (az/type
   [:*const
    [:fn {}
     [{:name color :type Color}
      {:name x :type :i32}
      {:name y :type :i32}
      {:name width :type :i32}
      {:name height :type :i32}]
     :void]]))

(az/defn effect-color
  "Five live-editable visual treatments shared by Vulkan and WebGL."
  {:attrs #{:public :implicit-return}}
  :-
  Color
  [[effect :u32]
   [phase :f32]
   [row :f32]]
  (let [pulse (+ 0.55 (* 0.45 (std-math/sin phase)))
        wave (+ 0.5 (* 0.5 (std-math/sin (+ phase (* row 9.0)))))]
    (cond
      (ak/== effect 0) (Color {:r 0.12 :g 0.78 :b 0.95 :a 1.0})
      (ak/== effect 1) (Color {:r 0.95 :g (* 0.45 pulse) :b 0.22 :a 1.0})
      (ak/== effect 2) (Color {:r (+ 0.15 (* 0.65 row)) :g 0.32 :b 0.92 :a 1.0})
      (ak/== effect 3) (Color {:r (* 0.2 wave) :g (+ 0.35 (* 0.6 wave)) :b 0.48 :a 1.0})
      :else (Color
             {:r (+ 0.45 (* 0.45 (std-math/sin (+ phase row))))
              :g (+ 0.45 (* 0.45 (std-math/sin (+ phase row 2.1))))
              :b (+ 0.45 (* 0.45 (std-math/sin (+ phase row 4.2))))
              :a 1.0}))))

(az/defn digit-mask
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[digit :u32]]
  (cond
    (ak/== digit 0) 126
    (ak/== digit 1) 48
    (ak/== digit 2) 109
    (ak/== digit 3) 121
    (ak/== digit 4) 51
    (ak/== digit 5) 91
    (ak/== digit 6) 95
    (ak/== digit 7) 112
    (ak/== digit 8) 127
    :else 123))

(az/defn segment-on?
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[mask :u8]
   [bit :u8]]
  (ak/!= (ak/& mask bit) 0))

(az/defn draw-small-digit
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [digit :u32]
   [color Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]
   [thickness :i32]]
  (let [mask (digit-mask digit)
        half-height (ak/divTrunc height 2)]
    (when (segment-on? mask 64)
      (draw-rect color x y width thickness))
    (when (segment-on? mask 32)
      (draw-rect color (+ x (- width thickness)) y thickness half-height))
    (when (segment-on? mask 16)
      (draw-rect color (+ x (- width thickness)) (+ y half-height)
                 thickness half-height))
    (when (segment-on? mask 8)
      (draw-rect color x (+ y (- height thickness)) width thickness))
    (when (segment-on? mask 4)
      (draw-rect color x (+ y half-height) thickness half-height))
    (when (segment-on? mask 2)
      (draw-rect color x y thickness half-height))
    (when (segment-on? mask 1)
      (draw-rect color x (+ y (- half-height (ak/divTrunc thickness 2)))
                 width thickness))))

(az/defn draw-circle
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [packet game/RenderPacket]
   [frame-width :i32]
   [frame-height :i32]]
  (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
        radius (az/field packet radius)
        ^{:var true} row (ak/as :i32 (ak/intFromFloat (- 0.0 radius)))]
    (ak/while (< row (ak/as :i32 (ak/intFromFloat radius)))
      (let [row-float (ak/as :f32 (ak/floatFromInt row))
            half-width (std-math/sqrt (- (* radius radius) (* row-float row-float)))
            normalized (/ (+ row-float radius) (* radius 2.0))
            color (effect-color (az/field packet shader_index)
                                (az/field packet phase)
                                normalized)
            x (ak/as :i32
                     (ak/intFromFloat
                      (* (- (az/field packet center_x) half-width) scale-x)))
            y (ak/as :i32
                     (ak/intFromFloat
                      (* (+ (az/field packet center_y) row-float) scale-y)))
            width (ak/as :i32 (ak/intFromFloat (* half-width 2.0 scale-x)))
            height (ak/as :i32 (ak/intFromFloat scale-y))]
        (draw-rect color x y width height))
      (set! row (+ row 1)))))

(az/defn draw-particle
  "Project one real Box3D sphere into the shared 2D scene."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [particle physics/ParticleView]
   [packet game/RenderPacket]
   [frame-width :i32]
   [frame-height :i32]]
  (when (az/field particle active)
    (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
          scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
          depth-scale (+ 1.0 (* (az/field particle z) 0.06))
          radius (* (az/field particle radius) 28.0 depth-scale)
          center-x (+ (az/field packet center_x)
                      (* (az/field particle x) 28.0))
          center-y (+ (az/field packet center_y)
                      (* (az/field particle y) 28.0)
                      (* (az/field particle z) 3.0))
          ^{:var true} row (ak/as :i32 (ak/intFromFloat (- 0.0 radius)))]
      (ak/while (< row (ak/as :i32 (ak/intFromFloat radius)))
        (let [row-float (ak/as :f32 (ak/floatFromInt row))
              half-width (std-math/sqrt
                          (- (* radius radius) (* row-float row-float)))
              normalized (/ (+ row-float radius) (* radius 2.0))
              color (effect-color (az/field particle tint)
                                  (+ (az/field particle age) normalized)
                                  normalized)
              x (ak/as :i32
                       (ak/intFromFloat (* (- center-x half-width) scale-x)))
              y (ak/as :i32
                       (ak/intFromFloat (* (+ center-y row-float) scale-y)))
              width (ak/as :i32
                           (ak/intFromFloat (* half-width 2.0 scale-x)))
              height (ak/as :i32 (ak/intFromFloat scale-y))]
          (draw-rect color x y width height))
        (set! row (+ row 1))))))

(az/defn draw-particles
  "Render the bounded Box3D pool through the same backend callback."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [packet game/RenderPacket]
   [frame-width :i32]
   [frame-height :i32]]
  (dotimes [slot physics/particle-capacity]
    (draw-particle draw-rect (physics/particle-view slot)
                   packet frame-width frame-height)))

(az/defn draw-counter
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [packet game/RenderPacket]
   [frame-width :i32]
   [frame-height :i32]]
  (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
        x (ak/as :i32 (ak/intFromFloat (* 485.0 scale-x)))
        y (ak/as :i32 (ak/intFromFloat (* 222.0 scale-y)))
        width (ak/as :i32 (ak/intFromFloat (* 42.0 scale-x)))
        height (ak/as :i32 (ak/intFromFloat (* 96.0 scale-y)))
        thickness-x (ak/as :i32 (ak/intFromFloat (* 9.0 scale-x)))
        thickness-y (ak/as :i32 (ak/intFromFloat (* 9.0 scale-y)))
        half-height (ak/divTrunc height 2)
        mask (digit-mask (mod (az/field packet counter) 10))
        color (Color {:r 0.96 :g 0.93 :b 0.72 :a 1.0})]
    (when (segment-on? mask 64)
      (draw-rect color x y width thickness-y))
    (when (segment-on? mask 32)
      (draw-rect color (+ x (- width thickness-x)) y thickness-x half-height))
    (when (segment-on? mask 16)
      (draw-rect color (+ x (- width thickness-x)) (+ y half-height)
                 thickness-x half-height))
    (when (segment-on? mask 8)
      (draw-rect color x (+ y (- height thickness-y)) width thickness-y))
    (when (segment-on? mask 4)
      (draw-rect color x (+ y half-height) thickness-x half-height))
    (when (segment-on? mask 2)
      (draw-rect color x y thickness-x half-height))
    (when (segment-on? mask 1)
      (draw-rect color x (+ y (- half-height (ak/divTrunc thickness-y 2)))
                 width thickness-y))))

(az/defn draw-fps
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (let [measured (game/current-fps)
        rounded (ak/as :u32 (ak/intFromFloat (+ measured 0.5)))
        fps (if (> rounded 999) 999 rounded)
        y (- frame-height 32)
        label-x (- frame-width 132)
        digits-x (- frame-width 64)
        color (Color {:r 0.70 :g 0.86 :b 0.82 :a 1.0})]
    ;; F
    (draw-rect color label-x y 3 24)
    (draw-rect color label-x y 15 3)
    (draw-rect color label-x (+ y 10) 12 3)
    ;; P
    (draw-rect color (+ label-x 21) y 3 24)
    (draw-rect color (+ label-x 21) y 15 3)
    (draw-rect color (+ label-x 33) y 3 12)
    (draw-rect color (+ label-x 21) (+ y 10) 15 3)
    ;; S
    (draw-small-digit draw-rect 5 color (+ label-x 42) y 15 24 3)
    (when (>= fps 100)
      (draw-small-digit draw-rect (ak/divTrunc fps 100)
                        color digits-x y 16 24 3))
    (when (>= fps 10)
      (draw-small-digit draw-rect (mod (ak/divTrunc fps 10) 10)
                        color (+ digits-x 22) y 16 24 3))
    (draw-small-digit draw-rect (mod fps 10)
                      color (+ digits-x 44) y 16 24 3)))

(az/defn draw-frame-timing
  "Draw 120-frame CPU work average, excluding pacing and presentation waits."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-height :i32]]
  (let [timing (host/frame-timing)
        average-ms (az/field timing work_average_ms)
        measured-tenths
        (ak/as :u32 (ak/intFromFloat (+ (* average-ms 10.0) 0.5)))
        tenths (if (> measured-tenths 999) 999 measured-tenths)
        whole (ak/divTrunc tenths 10)
        y (- frame-height 32)
        color (Color {:r 0.96 :g 0.66 :b 0.46 :a 1.0})]
    (when (>= whole 10)
      (draw-small-digit draw-rect (ak/divTrunc whole 10)
                        color 64 y 13 24 3))
    (draw-small-digit draw-rect (mod whole 10)
                      color 82 y 13 24 3)
    (draw-rect color 99 (+ y 20) 3 3)
    (draw-small-digit draw-rect (mod tenths 10)
                      color 107 y 13 24 3)))

(az/defn font-color
  {:attrs #{:public :implicit-return}}
  :-
  Color
  [[palette :u8]]
  (cond
    (ak/== palette 0) (Color {:r 0.94 :g 0.76 :b 0.38 :a 1.0})
    (ak/== palette 1) (Color {:r 0.46 :g 0.88 :b 0.72 :a 1.0})
    (ak/== palette 2) (Color {:r 0.73 :g 0.68 :b 0.98 :a 1.0})
    :else (Color {:r 0.96 :g 0.66 :b 0.46 :a 1.0})))

(az/defn draw-loaded-fonts
  "Draw cached spans produced from three runtime-loaded TrueType fonts."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)]
    (dotimes [index (font/rect-count)]
      (let [rectangle (font/rect-at index)
            x (ak/as :i32
                     (ak/intFromFloat
                      (* (ak/as :f32
                                (ak/floatFromInt (az/field rectangle x)))
                         scale-x)))
            y (ak/as :i32
                     (ak/intFromFloat
                      (* (ak/as :f32
                                (ak/floatFromInt (az/field rectangle y)))
                         scale-y)))
            width (ak/as :i32
                         (ak/intFromFloat
                          (* (ak/as :f32
                                    (ak/floatFromInt (az/field rectangle width)))
                             scale-x)))
            height (ak/as :i32
                          (ak/intFromFloat
                           (* (ak/as :f32
                                     (ak/floatFromInt (az/field rectangle height)))
                              scale-y)))]
        (draw-rect (font-color (az/field rectangle palette))
                   x y (ak/max width 1) (ak/max height 1))))))

(az/defn sprite-color
  {:attrs #{:public :implicit-return}}
  :-
  Color
  [[span animation/SpriteSpan]]
  (Color
   {:r (/ (ak/as :f32 (ak/floatFromInt (az/field span red))) 255.0)
    :g (/ (ak/as :f32 (ak/floatFromInt (az/field span green))) 255.0)
    :b (/ (ak/as :f32 (ak/floatFromInt (az/field span blue))) 255.0)
    :a (/ (ak/as :f32 (ak/floatFromInt (az/field span alpha))) 255.0)}))

(az/defn draw-sprite-animation
  "Draw the same native animation pack through either graphics backend."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (let [frame (animation/current-frame-view)
        screen-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        screen-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
        sprite-scale 2.5
        first (az/field frame first_span)]
    (dotimes [offset (az/field frame span_count)]
      (let [span (animation/span-at (+ first offset))
            x (ak/as :i32
                     (ak/intFromFloat
                      (* (+ 570.0
                            (* (ak/as :f32
                                      (ak/floatFromInt (az/field span x)))
                               sprite-scale))
                         screen-x)))
            y (ak/as :i32
                     (ak/intFromFloat
                      (* (+ 112.0
                            (* (ak/as :f32
                                      (ak/floatFromInt (az/field span y)))
                               sprite-scale))
                         screen-y)))
            width (ak/as :i32
                         (ak/intFromFloat
                          (* (ak/as :f32
                                    (ak/floatFromInt (az/field span width)))
                             sprite-scale
                             screen-x)))
            height (ak/as :i32
                          (ak/intFromFloat (* sprite-scale screen-y)))]
        (draw-rect (sprite-color span)
                   x y (ak/max width 1) (ak/max height 1))))))

(az/defn draw-frame
  "Construct the complete scene once and dispatch rectangles to a backend."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [packet game/RenderPacket]
   [frame-width :i32]
   [frame-height :i32]]
  (draw-circle draw-rect packet frame-width frame-height)
  (draw-particles draw-rect packet frame-width frame-height)
  (draw-counter draw-rect packet frame-width frame-height)
  (draw-sprite-animation draw-rect frame-width frame-height)
  (draw-loaded-fonts draw-rect frame-width frame-height)
  (draw-frame-timing draw-rect frame-height)
  (draw-fps draw-rect frame-width frame-height))
