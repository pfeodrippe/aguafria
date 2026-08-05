(ns simple-game.hud
  "Native coco-factory HUD shared by the development and standalone renderers."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [simple-game.factory :as factory]
            [simple-game.font :as font]))

(az/defstruct Color
  "Linear RGBA color consumed by the Vulkan overlay callback."
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

(az/defn font-color
  {:attrs #{:public :implicit-return}}
  :-
  Color
  [[palette :u8]]
  (cond
    (ak/== palette 4) (Color {:r 1.0 :g 0.83 :b 0.38 :a 1.0})
    (ak/== palette 5) (Color {:r 0.45 :g 0.88 :b 0.65 :a 1.0})
    (ak/== palette 6) (Color {:r 0.42 :g 0.80 :b 0.96 :a 1.0})
    (ak/== palette 7) (Color {:r 1.0 :g 0.72 :b 0.36 :a 1.0})
    (ak/== palette 8) (Color {:r 0.95 :g 0.53 :b 0.61 :a 1.0})
    (ak/== palette 10) (Color {:r 0.48 :g 1.0 :b 0.62 :a 1.0})
    :else (Color {:r 0.86 :g 0.91 :b 0.82 :a 1.0})))

(az/defn control-building-kind
  "Map one cached control-label palette to its native building kind."
  {:export false :implicit-return true}
  :-
  :u8
  [[palette :u8]]
  (cond
    (ak/== palette 11) factory/building-belt
    (ak/== palette 12) factory/building-extractor
    (ak/== palette 14) factory/building-assembler
    (ak/== palette 15) factory/building-splitter
    (ak/== palette 16) factory/building-coco-house
    :else factory/building-empty))

(az/defn digit-mask
  {:export false :implicit-return true}
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
  {:export false :implicit-return true}
  :-
  :bool
  [[mask :u8]
   [bit :u8]]
  (ak/!= (ak/& mask bit) 0))

(az/defn draw-small-digit
  {:export false}
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

(az/defn draw-hud-number
  "Draw a bounded native statistic without allocating each frame."
  {:export false}
  :-
  :void
  [[draw-rect DrawRect]
   [value :u32]
   [x :i32]
   [y :i32]
   [color Color]
   [frame-width :i32]
   [frame-height :i32]]
  (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
        screen-x (ak/as :i32
                        (ak/intFromFloat
                         (* (ak/as :f32 (ak/floatFromInt x)) scale-x)))
        screen-y (ak/as :i32
                        (ak/intFromFloat
                         (* (ak/as :f32 (ak/floatFromInt y)) scale-y)))
        width (ak/max 1 (ak/as :i32 (ak/intFromFloat (* 9.0 scale-x))))
        height (ak/max 1 (ak/as :i32 (ak/intFromFloat (* 14.0 scale-y))))
        thickness (ak/max 1 (ak/as :i32 (ak/intFromFloat (* 2.0 scale-x))))
        spacing (ak/max 1 (ak/as :i32 (ak/intFromFloat (* 14.0 scale-x))))
        bounded (ak/min value 999)]
    (when (>= bounded 100)
      (draw-small-digit draw-rect (ak/divTrunc bounded 100)
                        color screen-x screen-y width height thickness))
    (when (>= bounded 10)
      (draw-small-digit draw-rect (mod (ak/divTrunc bounded 10) 10)
                        color (+ screen-x spacing) screen-y
                        width height thickness))
    (draw-small-digit draw-rect (mod bounded 10)
                      color (+ screen-x (* spacing 2)) screen-y
                      width height thickness)))

(az/defn draw-overlay
  "Draw the complete allocation-free coco-factory HUD over the 3D mesh pass."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (when (and (> frame-width 0) (> frame-height 0))
    (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
          scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
          state (factory/snapshot)
          panel-x (ak/as :i32 (ak/intFromFloat (* 18.0 scale-x)))
          panel-y (ak/as :i32 (ak/intFromFloat (* 444.0 scale-y)))
          panel-width (ak/as :i32 (ak/intFromFloat (* 684.0 scale-x)))
          panel-height (ak/as :i32 (ak/intFromFloat (* 66.0 scale-y)))]
      (draw-rect (Color {:r 0.055 :g 0.11 :b 0.16 :a 1.0})
                 0 0 frame-width
                 (ak/as :i32 (ak/intFromFloat (* 68.0 scale-y))))
      (draw-rect (Color {:r 0.94 :g 0.48 :b 0.20 :a 1.0})
                 0 (ak/as :i32 (ak/intFromFloat (* 66.0 scale-y)))
                 frame-width (ak/max 1 (ak/as :i32
                                              (ak/intFromFloat (* 2.0 scale-y)))))
      (draw-rect (Color {:r 0.055 :g 0.11 :b 0.16 :a 1.0})
                 panel-x panel-y panel-width panel-height)
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
          (let [palette (az/field rectangle palette)]
            (when (and (>= palette 4)
                       (or (ak/!= palette 10)
                           (az/field state objective_complete)))
              (let [control-kind (control-building-kind palette)
                    color
                    (if (and (ak/!= control-kind factory/building-empty)
                             (ak/== control-kind (az/field state build_kind)))
                      (Color {:r 1.0 :g 0.78 :b 0.26 :a 1.0})
                      (font-color palette))]
                (draw-rect color
                           x y (ak/max width 1) (ak/max height 1)))))))
      (draw-hud-number draw-rect (az/field state coconuts_harvested) 250 35
                       (font-color 5) frame-width frame-height)
      (draw-hud-number draw-rect (az/field state panels_produced) 360 35
                       (font-color 6) frame-width frame-height)
      (draw-hud-number draw-rect (az/field state houses_completed) 480 35
                       (font-color 7) frame-width frame-height)
      (draw-hud-number draw-rect (az/field state house_goal) 610 35
                       (font-color 8) frame-width frame-height))))
