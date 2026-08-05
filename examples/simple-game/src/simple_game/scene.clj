(ns simple-game.scene
  "Renderer-independent scene construction shared by desktop and WebAssembly."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.animation :as animation]
            [simple-game.cart :as cart]
            [simple-game.factory :as factory]
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

(az/defstruct DrawCommand
  "One backend-neutral rectangle in the bounded native render queue."
  {:layout :extern}
  [[:color Color]
   [:x :i32]
   [:y :i32]
   [:width :i32]
   [:height :i32]])

(az/defstruct SceneSnapshot
  "Inspectable render submission statistics for the latest frame."
  {:layout :extern}
  [[:submitted :u32]
   [:flushed :u32]
   [:dropped :u32]
   [:capacity :u32]])

(az/defconst command-capacity :usize 16384)

(az/defvar commands [:array 16384 DrawCommand]
  (std-mem/zeroes (az/type [:array 16384 DrawCommand])))

(az/defvar command-count :usize 0)

(az/defvar submitted-count :u32 0)

(az/defvar flushed-count :u32 0)

(az/defvar dropped-count :u32 0)

(az/defvar frame-scale-x :f32 1.0)

(az/defvar frame-scale-y :f32 1.0)

(az/defn begin-commands!
  {:export false}
  :-
  :void
  []
  (set! command-count 0)
  (set! submitted-count 0)
  (set! flushed-count 0)
  (set! dropped-count 0))

(az/defn queue-rect!
  "Append one operation without allocating; overflow is visible, never unsafe."
  {:attrs #{:public}}
  :-
  :void
  [[color Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]]
  (when (and (> width 0) (> height 0))
    (set! submitted-count (+ submitted-count 1))
    (if (< command-count command-capacity)
      (do
        (set! (az/index commands command-count)
              (DrawCommand {:color color
                            :x x :y y :width width :height height}))
        (set! command-count (+ command-count 1)))
      (set! dropped-count (+ dropped-count 1)))))

(az/defn flush-commands!
  {:export false}
  :-
  :void
  [[draw-rect DrawRect]]
  (dotimes [index command-count]
    (let [command (az/index commands index)
          x (ak/as :i32
                   (ak/intFromFloat
                    (* (ak/as :f32 (ak/floatFromInt (az/field command x)))
                       frame-scale-x)))
          y (ak/as :i32
                   (ak/intFromFloat
                    (* (ak/as :f32 (ak/floatFromInt (az/field command y)))
                       frame-scale-y)))
          width (ak/as :i32
                       (ak/intFromFloat
                        (* (ak/as :f32
                                  (ak/floatFromInt (az/field command width)))
                           frame-scale-x)))
          height (ak/as :i32
                        (ak/intFromFloat
                         (* (ak/as :f32
                                   (ak/floatFromInt (az/field command height)))
                            frame-scale-y)))]
      (draw-rect (az/field command color)
                 x y (ak/max width 1) (ak/max height 1))))
  (set! flushed-count (ak/intCast command-count)))

(az/defn snapshot
  :-
  SceneSnapshot
  []
  (SceneSnapshot {:submitted submitted-count
                  :flushed flushed-count
                  :dropped dropped-count
                  :capacity (ak/intCast command-capacity)}))

(az/defn terrain-color
  "Recife terrain palette for water, sand, calçada, and mangrove ground."
  {:attrs #{:public :implicit-return}}
  :-
  Color
  [[terrain :u8]
   [checker :bool]]
  (cond
    (ak/== terrain factory/terrain-water)
    (if checker
      (Color {:r 0.055 :g 0.34 :b 0.46 :a 1.0})
      (Color {:r 0.04 :g 0.27 :b 0.39 :a 1.0}))

    (ak/== terrain factory/terrain-sand)
    (Color {:r 0.76 :g 0.63 :b 0.39 :a 1.0})

    (ak/== terrain factory/terrain-mangrove)
    (Color {:r 0.13 :g 0.38 :b 0.25 :a 1.0})

    (ak/== terrain factory/terrain-cobblestone)
    (if checker
      (Color {:r 0.43 :g 0.42 :b 0.40 :a 1.0})
      (Color {:r 0.51 :g 0.49 :b 0.45 :a 1.0}))

    checker
    (Color {:r 0.86 :g 0.83 :b 0.72 :a 1.0})

    :else
    (Color {:r 0.72 :g 0.70 :b 0.63 :a 1.0})))

(az/defn iso-x
  {:attrs #{:public :implicit-return}}
  :-
  :i32
  [[x :i32]
   [y :i32]]
  (+ 360 (* (- x y) 8)))

(az/defn iso-y
  {:attrs #{:public :implicit-return}}
  :-
  :i32
  [[x :i32]
   [y :i32]
   [z :i32]]
  (- (+ 72 (* (+ x y) 4)) (* z 10)))

(az/defn queue-diamond!
  "Rasterize a small isometric tile top into the native command queue."
  {:export false}
  :-
  :void
  [[color Color]
   [center-x :i32]
   [top-y :i32]]
  (dotimes [row 8]
    (let [row-i (ak/as :i32 (ak/intCast row))
          half-width (if (< row-i 4)
                       (* (+ row-i 1) 2)
                       (* (- 8 row-i) 2))]
      (queue-rect! color (- center-x half-width) (+ top-y row-i)
                   (* half-width 2) 1))))

(az/defn queue-box!
  "Draw a compact shaded isometric building with explicit height."
  {:export false}
  :-
  :void
  [[top Color]
   [side Color]
   [center-x :i32]
   [base-y :i32]
   [height :i32]
   [wide :bool]]
  (let [^{:zig/type :i32} width (if wide 13 9)
        top-y (- base-y height)]
    (queue-rect! side (- center-x width) (+ top-y 4)
                 (* width 2) (+ height 4))
    (queue-diamond! top center-x top-y)
    (queue-rect! (Color {:r 0.08 :g 0.10 :b 0.12 :a 1.0})
                 center-x (+ top-y 5) width (+ height 1))))

(az/defn queue-facade!
  "Compose one narrow pastel Recife Antigo façade behind the playable block."
  {:export false}
  :-
  :void
  [[wall Color]
   [trim Color]
   [x :i32]
   [base-y :i32]
   [width :i32]
   [height :i32]]
  (let [top (- base-y height)
        window (Color {:r 0.06 :g 0.25 :b 0.29 :a 1.0})
        roof (Color {:r 0.58 :g 0.20 :b 0.11 :a 1.0})]
    (queue-rect! (Color {:r 0.20 :g 0.18 :b 0.17 :a 1.0})
                 (+ x 4) (+ top 5) width height)
    (queue-rect! wall x top width height)
    (queue-rect! trim x top width 4)
    (queue-rect! roof (- x 2) (- top 5) (+ width 4) 6)
    (queue-rect! trim (+ x 5) (+ top 13) (- width 10) 3)
    (queue-rect! window (+ x 7) (+ top 20) 10 17)
    (queue-rect! window (- (+ x width) 17) (+ top 20) 10 17)
    (queue-rect! trim (+ x 5) (+ top 39) (- width 10) 3)
    (queue-rect! window (+ x 7) (+ top 48) 10 (- height 53))
    (queue-rect! window (- (+ x width) 17) (+ top 48) 10 (- height 53))))

(az/defn queue-palm!
  "Small isometric palm silhouette matching the selected Kenney palm asset."
  {:export false}
  :-
  :void
  [[x :i32]
   [base-y :i32]
   [height :i32]]
  (let [trunk (Color {:r 0.42 :g 0.25 :b 0.12 :a 1.0})
        leaf-dark (Color {:r 0.07 :g 0.34 :b 0.17 :a 1.0})
        leaf (Color {:r 0.12 :g 0.55 :b 0.26 :a 1.0})
        crown-y (- base-y height)]
    (queue-rect! trunk (- x 2) crown-y 4 height)
    (queue-rect! leaf-dark (- x 22) (- crown-y 3) 44 5)
    (queue-rect! leaf (- x 17) (- crown-y 10) 34 5)
    (queue-rect! leaf-dark (- x 5) (- crown-y 18) 10 28)
    (queue-rect! leaf (- x 14) (- crown-y 14) 28 5)))

(az/defn queue-street-lamp!
  {:export false}
  :-
  :void
  [[x :i32]
   [base-y :i32]]
  (let [metal (Color {:r 0.08 :g 0.09 :b 0.10 :a 1.0})
        glass (Color {:r 1.0 :g 0.82 :b 0.42 :a 1.0})]
    (queue-rect! metal (- x 2) (- base-y 38) 4 38)
    (queue-rect! metal (- x 6) (- base-y 40) 12 3)
    (queue-rect! metal (- x 5) (- base-y 50) 10 11)
    (queue-rect! glass (- x 3) (- base-y 48) 6 7)
    (queue-rect! metal (- x 7) (- base-y 2) 14 3)))

(az/defn queue-recife-backdrop!
  "Frame the cart with colorful façades, palms, and plaza street furniture."
  {:export false}
  :-
  :void
  []
  (queue-facade! (Color {:r 0.91 :g 0.30 :b 0.31 :a 1.0})
                   (Color {:r 0.98 :g 0.86 :b 0.72 :a 1.0})
                   80 136 68 92)
  (queue-facade! (Color {:r 0.96 :g 0.69 :b 0.23 :a 1.0})
                   (Color {:r 0.99 :g 0.92 :b 0.72 :a 1.0})
                   150 136 72 105)
  (queue-facade! (Color {:r 0.33 :g 0.70 :b 0.76 :a 1.0})
                   (Color {:r 0.92 :g 0.94 :b 0.88 :a 1.0})
                   500 136 66 98)
  (queue-facade! (Color {:r 0.94 :g 0.42 :b 0.24 :a 1.0})
                   (Color {:r 0.99 :g 0.78 :b 0.52 :a 1.0})
                   568 136 68 112)
  (queue-palm! 240 146 74)
  (queue-palm! 470 146 68)
  (queue-street-lamp! 270 170)
  (queue-street-lamp! 458 170))

(az/defn queue-coconut-cart!
  "Draw the street cart that the player operates and improves."
  {:export false}
  :-
  :void
  [[state cart/CartSnapshot]]
  (let [level (az/field state cart_level)
        x 467
        y 279
        wood (Color {:r 0.65 :g 0.28 :b 0.10 :a 1.0})
        teal (Color {:r 0.04 :g 0.58 :b 0.54 :a 1.0})
        cream (Color {:r 0.98 :g 0.87 :b 0.55 :a 1.0})
        coconut (Color {:r 0.18 :g 0.55 :b 0.20 :a 1.0})]
    (queue-rect! wood (- x 27) (- y 42) 54 34)
    (queue-rect! teal (- x 31) (- y 46) 62 8)
    (queue-rect! cream (- x 32) (- y 74) 64 9)
    (queue-rect! teal (- x 32) (- y 74) 13 9)
    (queue-rect! teal (- x 6) (- y 74) 13 9)
    (queue-rect! teal (+ x 20) (- y 74) 12 9)
    (queue-rect! wood (- x 27) (- y 65) 4 24)
    (queue-rect! wood (+ x 23) (- y 65) 4 24)
    (queue-rect! (Color {:r 0.06 :g 0.07 :b 0.08 :a 1.0})
                 (- x 23) (- y 12) 14 14)
    (queue-rect! (Color {:r 0.06 :g 0.07 :b 0.08 :a 1.0})
                 (+ x 9) (- y 12) 14 14)
    (dotimes [index (ak/min (az/field state coconuts) 6)]
      (queue-rect! coconut
                   (+ (- x 23) (* (ak/as :i32 (ak/intCast index)) 8))
                   (- y 36) 6 6))
    (when (> level 0)
      (queue-rect! cream (- x 10) (- y 91) 20 17)
      (queue-rect! teal (- x 7) (- y 88) 14 3))))

(az/defn queue-customer!
  "Show the current customer waiting beside the cart."
  {:export false}
  :-
  :void
  [[state cart/CartSnapshot]]
  (when (> (az/field state waiting) 0)
    (let [x 526
          y 282
          shirt (cond
                  (ak/== (az/field state customer) 0)
                  (Color {:r 0.25 :g 0.58 :b 0.90 :a 1.0})
                  (ak/== (az/field state customer) 1)
                  (Color {:r 0.88 :g 0.34 :b 0.50 :a 1.0})
                  :else (Color {:r 0.95 :g 0.70 :b 0.19 :a 1.0}))
          skin (Color {:r 0.56 :g 0.30 :b 0.18 :a 1.0})]
      (queue-rect! skin (- x 6) (- y 45) 12 13)
      (queue-rect! shirt (- x 9) (- y 32) 18 24)
      (queue-rect! (Color {:r 0.10 :g 0.16 :b 0.27 :a 1.0})
                   (- x 8) (- y 8) 6 13)
      (queue-rect! (Color {:r 0.10 :g 0.16 :b 0.27 :a 1.0})
                   (+ x 2) (- y 8) 6 13))))

(az/defn queue-belt!
  {:export false}
  :-
  :void
  [[view factory/CellView]
   [center-x :i32]
   [top-y :i32]]
  (let [direction (az/field view direction)
        belt-color (Color {:r 0.16 :g 0.19 :b 0.22 :a 1.0})
        marker (Color {:r 0.92 :g 0.60 :b 0.18 :a 1.0})]
    (queue-diamond! belt-color center-x top-y)
    (if (or (ak/== direction factory/direction-east)
            (ak/== direction factory/direction-west))
      (queue-rect! marker (- center-x 5) (+ top-y 3) 10 2)
      (queue-rect! marker (- center-x 1) (+ top-y 1) 2 6))))

(az/defn queue-item!
  {:export false}
  :-
  :void
  [[view factory/CellView]
   [center-x :i32]
   [top-y :i32]]
  (when (ak/!= (az/field view item_kind) factory/item-none)
    (let [progress (az/field view item_progress)
          direction (az/field view direction)
          dx (cond
               (ak/== direction factory/direction-east) (* progress 8.0)
               (ak/== direction factory/direction-west) (* progress -8.0)
               (ak/== direction factory/direction-south) (* progress -8.0)
               :else (* progress 8.0))
          dy (cond
               (ak/== direction factory/direction-east) (* progress 4.0)
               (ak/== direction factory/direction-west) (* progress -4.0)
               (ak/== direction factory/direction-south) (* progress 4.0)
               :else (* progress -4.0))
          item-x (+ center-x (ak/as :i32 (ak/intFromFloat dx)))
          item-y (+ top-y 1 (ak/as :i32 (ak/intFromFloat dy)))
          color (if (ak/== (az/field view item_kind) factory/item-coconut)
                  (Color {:r 0.18 :g 0.55 :b 0.20 :a 1.0})
                  (Color {:r 0.94 :g 0.35 :b 0.20 :a 1.0}))]
      (queue-rect! (Color {:r 0.03 :g 0.04 :b 0.05 :a 1.0})
                   (- item-x 2) (+ item-y 1) 5 4)
      (queue-rect! color (- item-x 2) (- item-y 2) 5 4))))

(az/defn queue-factory-cell!
  {:export false}
  :-
  :void
  [[view factory/CellView]]
  (let [x (az/field view x)
        y (az/field view y)
        center-x (iso-x x y)
        top-y (iso-y x y 0)
        checker (ak/== (mod (+ x y) 2) 0)
        building (az/field view building)]
    (queue-diamond! (terrain-color (az/field view terrain) checker)
                    center-x top-y)
    (when (ak/== building factory/building-belt)
      (queue-belt! view center-x (- top-y 2)))
    (when (ak/== building factory/building-extractor)
      (queue-box! (Color {:r 0.95 :g 0.51 :b 0.17 :a 1.0})
                  (Color {:r 0.48 :g 0.19 :b 0.08 :a 1.0})
                  center-x top-y 17 true))
    (when (ak/== building factory/building-storage)
      (queue-box! (Color {:r 0.25 :g 0.72 :b 0.68 :a 1.0})
                  (Color {:r 0.08 :g 0.32 :b 0.35 :a 1.0})
                  center-x top-y 14 true))
    (when (ak/== building factory/building-assembler)
      (queue-box! (Color {:r 0.60 :g 0.39 :b 0.86 :a 1.0})
                  (Color {:r 0.24 :g 0.15 :b 0.42 :a 1.0})
                  center-x top-y 20 true))
    (when (ak/== building factory/building-splitter)
      (queue-box! (Color {:r 0.85 :g 0.73 :b 0.24 :a 1.0})
                  (Color {:r 0.37 :g 0.29 :b 0.08 :a 1.0})
                  center-x top-y 8 true))
    (queue-item! view center-x (- top-y 4))))

(az/defn draw-factory
  "Submit the complete Recife world in diagonal isometric depth order."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]]
  (set! _ draw-rect)
  (queue-recife-backdrop!)
  (dotimes [depth (+ factory/grid-width factory/grid-height)]
    (dotimes [x factory/grid-width]
      (let [depth-i (ak/as :i32 (ak/intCast depth))
            x-i (ak/as :i32 (ak/intCast x))
            y-i (- depth-i x-i)]
        (when (factory/valid-cell? x-i y-i)
          (queue-factory-cell! (factory/cell-view x-i y-i))))))
  (let [state (factory/snapshot)
        selected-x (az/field state selected_x)
        selected-y (az/field state selected_y)]
    (when (factory/valid-cell? selected-x selected-y)
      (let [x (iso-x selected-x selected-y)
            y (iso-y selected-x selected-y 0)
            color (Color {:r 0.98 :g 0.92 :b 0.34 :a 1.0})]
        (queue-rect! color (- x 8) y 16 1)
        (queue-rect! color (- x 8) (+ y 7) 16 1)
        (queue-rect! color (- x 8) y 1 8)
        (queue-rect! color (+ x 7) y 1 8))))
  (let [cart-state (cart/snapshot)]
    (queue-coconut-cart! cart-state)
    (queue-customer! cart-state)))

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
    (ak/== palette 0) (Color {:r 0.25 :g 0.16 :b 0.08 :a 1.0})
    (ak/== palette 1) (Color {:r 0.04 :g 0.31 :b 0.27 :a 1.0})
    (ak/== palette 2) (Color {:r 0.16 :g 0.20 :b 0.40 :a 1.0})
    (ak/== palette 4) (Color {:r 1.0 :g 0.83 :b 0.38 :a 1.0})
    (ak/== palette 5) (Color {:r 0.45 :g 0.88 :b 0.65 :a 1.0})
    (ak/== palette 6) (Color {:r 0.42 :g 0.80 :b 0.96 :a 1.0})
    (ak/== palette 7) (Color {:r 1.0 :g 0.72 :b 0.36 :a 1.0})
    (ak/== palette 8) (Color {:r 0.95 :g 0.53 :b 0.61 :a 1.0})
    (ak/== palette 9) (Color {:r 0.86 :g 0.91 :b 0.82 :a 1.0})
    :else (Color {:r 0.96 :g 0.66 :b 0.46 :a 1.0})))

(az/defn draw-hud-number
  "Draw a bounded native factory statistic without allocating each frame."
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

(az/defn draw-loaded-fonts
  "Draw cached spans produced from three runtime-loaded TrueType fonts."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (let [scale-x (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0)
        scale-y (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0)
        state (factory/snapshot)
        panel-x (ak/as :i32 (ak/intFromFloat (* 18.0 scale-x)))
        panel-y (ak/as :i32 (ak/intFromFloat (* 444.0 scale-y)))
        panel-width (ak/as :i32 (ak/intFromFloat (* 684.0 scale-x)))
        panel-height (ak/as :i32 (ak/intFromFloat (* 66.0 scale-y)))]
    ;; The top bar makes the cart economy readable at a glance and visually
    ;; frames the 3D street instead of leaving it floating in the clear color.
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
        (when (>= (az/field rectangle palette) 4)
          (draw-rect (font-color (az/field rectangle palette))
                     x y (ak/max width 1) (ak/max height 1)))))
    (draw-hud-number draw-rect (az/field state coconuts_harvested) 250 35
                     (font-color 5) frame-width frame-height)
    (draw-hud-number draw-rect (az/field state panels_produced) 360 35
                     (font-color 6) frame-width frame-height)
    (draw-hud-number draw-rect (az/field state houses_completed) 480 35
                     (font-color 7) frame-width frame-height)
    (draw-hud-number draw-rect (az/field state house_goal) 610 35
                     (font-color 8) frame-width frame-height)))

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
  (set! _ packet)
  (set! frame-scale-x
        (/ (ak/as :f32 (ak/floatFromInt frame-width)) 720.0))
  (set! frame-scale-y
        (/ (ak/as :f32 (ak/floatFromInt frame-height)) 540.0))
  (begin-commands!)
  (draw-factory draw-rect)
  (flush-commands! draw-rect)
  (draw-loaded-fonts draw-rect frame-width frame-height))

(az/defn draw-overlay
  "Draw only native UI/text over the desktop's real Kenney mesh scene."
  {:attrs #{:public}}
  :-
  :void
  [[draw-rect DrawRect]
   [frame-width :i32]
   [frame-height :i32]]
  (when (and (> frame-width 0) (> frame-height 0))
    (draw-loaded-fonts draw-rect frame-width frame-height)))
