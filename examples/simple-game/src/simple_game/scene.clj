(ns simple-game.scene
  "Renderer-independent scene construction shared by desktop and WebAssembly."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [simple-game.game :as game]))

(az/defn ColorType
  "Produce the cross-backend color type at Zig comptime."
  {:attrs #{:public :implicit-return}}
  :-
  :type
  []
  (az/container
   {:kind :struct :layout :extern}
   (az/field-decl r :f32)
   (az/field-decl g :f32)
   (az/field-decl b :f32)
   (az/field-decl a :f32)))

(az/defconst Color
  "Linear RGBA color consumed by every graphics backend."
  {:attrs #{:public}}
  (ColorType))

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
        ^{:var true :zig/type :i32} row (ak/intFromFloat (- 0.0 radius))]
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
  (draw-counter draw-rect packet frame-width frame-height)
  (draw-fps draw-rect frame-width frame-height))
