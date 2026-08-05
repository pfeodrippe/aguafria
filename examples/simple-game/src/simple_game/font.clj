(ns simple-game.font
  "Runtime-loaded TrueType fonts and cached text geometry shared by every target."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings]
            [simple-game.bindings.stb-truetype :as stb]
            [simple-game.bindings.stdio :as stdio]))

(az/defconst font-count :usize 3)

(az/defconst font-data-capacity :usize 524288)

(az/defconst atlas-width :usize 1024)

(az/defconst atlas-height :usize 256)

(az/defconst glyph-first :u21 32)

(az/defconst glyph-count :usize 224)

(az/defconst rectangle-capacity :usize 8192)

(az/defn FontStorageType
  "Native storage for one loaded TTF and its stb_truetype baked atlas."
  {:attrs #{:public :implicit-return}}
  :-
  :type
  []
  (az/container
   {:kind :struct}
   (az/field-decl data [:array 524288 :u8])
   (az/field-decl atlas [:array 262144 :u8])
   (az/field-decl characters [:array 224 stb/stbtt_bakedchar])
   (az/field-decl bytes :usize)
   (az/field-decl ready :bool)))

(az/defconst FontStorage
  {:attrs #{:public}}
  (FontStorageType))

(az/defstruct FontRect
  "One cached horizontal glyph span in framebuffer coordinates."
  {:layout :extern}
  [[:x :i32]
   [:y :i32]
   [:width :i32]
   [:height :i32]
   [:palette :u8]])

(az/defstruct FontSnapshot
  "Inspectable loaded-font and cached-layout state."
  {:layout :extern}
  [[:initialized :bool]
   [:loaded_fonts :u32]
   [:rectangles :u32]
   [:capacity :u32]])

(az/defvar initialized false)

(az/defvar fonts [:array 3 FontStorage]
  (std-mem/zeroes (az/type [:array 3 FontStorage])))

(az/defvar rectangles [:array 8192 FontRect]
  (std-mem/zeroes (az/type [:array 8192 FontRect])))

(az/defvar rectangle-count :usize 0)

(az/defvar dialogue-starts [:array 5 :usize]
  (std-mem/zeroes (az/type [:array 5 :usize])))

(az/defvar dialogue-counts [:array 5 :usize]
  (std-mem/zeroes (az/type [:array 5 :usize])))

(az/defn utf8-width-at
  {:export false :implicit-return true}
  :-
  :usize
  [[text [:slice-const :u8]]
   [index :usize]]
  (let [first (az/index text index)]
    (cond
      (< first 128) 1
      (< first 224) 2
      (< first 240) 3
      :else 4)))

(az/defn utf8-codepoint-at
  "Decode one trusted UTF-8 codepoint from application text."
  {:export false :implicit-return true}
  :-
  :u21
  [[text [:slice-const :u8]]
   [index :usize]]
  (let [first (az/index text index)
        width (utf8-width-at text index)]
    (cond
      (ak/== width 1) (ak/intCast first)
      (ak/== width 2)
      (ak/intCast
       (ak/| (ak/<< (ak/as :u32 (ak/& first 31)) 6)
             (ak/as :u32 (ak/& (az/index text (+ index 1)) 63))))
      (ak/== width 3)
      (ak/intCast
       (ak/|
        (ak/| (ak/<< (ak/as :u32 (ak/& first 15)) 12)
              (ak/<< (ak/as :u32 (ak/& (az/index text (+ index 1)) 63)) 6))
        (ak/as :u32 (ak/& (az/index text (+ index 2)) 63))))
      :else
      (ak/intCast
       (ak/|
        (ak/|
         (ak/<< (ak/as :u32 (ak/& first 7)) 18)
         (ak/<< (ak/as :u32 (ak/& (az/index text (+ index 1)) 63)) 12))
        (ak/|
         (ak/<< (ak/as :u32 (ak/& (az/index text (+ index 2)) 63)) 6)
         (ak/as :u32 (ak/& (az/index text (+ index 3)) 63))))))))

(az/defn load-font!
  "Read one TTF through portable C stdio and bake Latin-1 glyphs with stb."
  {:export false}
  :-
  :bool
  [[slot :usize]
   [path [:pointer {:size :c :const? true} :u8]]
   [pixel-height :f32]]
  (let [font (ak/& (az/index fonts slot))
        file (stdio/fopen path "rb")
        bytes (if (ak/== file null)
                0
                (let [read (stdio/fread
                            (ak/& (az/index (az/field (az/deref font) data) 0))
                            1 font-data-capacity file)]
                  (set! _ (stdio/fclose file))
                  read))]
    (set! (az/field (az/deref font) bytes) bytes)
    (if (ak/== bytes 0)
      false
      (let [baked
            (stb/stbtt_BakeFontBitmap
             (ak/& (az/index (az/field (az/deref font) data) 0))
             0
             pixel-height
             (ak/& (az/index (az/field (az/deref font) atlas) 0))
             (ak/intCast atlas-width)
             (ak/intCast atlas-height)
             (ak/intCast glyph-first)
             (ak/intCast glyph-count)
             (ak/& (az/index (az/field (az/deref font) characters) 0)))]
        (set! (az/field (az/deref font) ready) (> baked 0))
        (az/field (az/deref font) ready)))))

(az/defn append-rectangle!
  :-
  :void
  [[x :i32]
   [y :i32]
   [width :i32]
   [height :i32]
   [palette :u8]]
  (when (and (> width 0) (< rectangle-count rectangle-capacity))
    (set! (az/index rectangles rectangle-count)
          (FontRect {:x x :y y :width width :height height
                     :palette palette}))
    (set! rectangle-count (+ rectangle-count 1))))

(az/defn atlas-pixel
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[font [:* FontStorage]]
   [x :usize]
   [y :usize]]
  (az/index (az/field (az/deref font) atlas) (+ (* y atlas-width) x)))

(az/defn layout-text!
  "Raster-layout UTF-8 text once and cache horizontal spans for every frame."
  {:export false}
  :-
  :void
  [[font-index :usize]
   [text [:slice-const :u8]]
   [start-x :i32]
   [baseline-y :i32]
   [palette :u8]]
  (let [font (ak/& (az/index fonts font-index))
        ^{:var true :zig/type :usize} index 0
        ^{:var true :zig/type :f32}
        cursor (ak/as :f32 (ak/floatFromInt start-x))]
    (ak/while (< index (az/field text len))
      (let [decoded (utf8-codepoint-at text index)
            codepoint (if (and (>= decoded glyph-first)
                               (< decoded (+ glyph-first glyph-count)))
                        decoded
                        63)
            character-index (- codepoint glyph-first)
            character (ak/& (az/index
                              (az/field (az/deref font) characters)
                              character-index))
            x0 (ak/as :i32 (ak/intCast (az/field (az/deref character) x0)))
            y0 (ak/as :i32 (ak/intCast (az/field (az/deref character) y0)))
            x1 (ak/as :i32 (ak/intCast (az/field (az/deref character) x1)))
            y1 (ak/as :i32 (ak/intCast (az/field (az/deref character) y1)))
            width (- x1 x0)
            height (- y1 y0)
            glyph-x (+ (ak/as :i32 (ak/intFromFloat cursor))
                       (ak/as :i32
                              (ak/intFromFloat
                               (az/field (az/deref character) xoff))))
            glyph-y (+ baseline-y
                       (ak/as :i32
                              (ak/intFromFloat
                               (az/field (az/deref character) yoff))))
            ^{:var true :zig/type :i32} row 0]
        (ak/while (< row height)
          (let [^{:var true :zig/type :i32} column 0]
            (ak/while (< column width)
              (let [pixel (atlas-pixel
                           font
                           (ak/intCast (+ x0 column))
                           (ak/intCast (+ y0 row)))]
                (if (> pixel 80)
                  (let [run-start column]
                    (ak/while
                     (and (< column width)
                          (> (atlas-pixel
                              font
                              (ak/intCast (+ x0 column))
                              (ak/intCast (+ y0 row)))
                             80))
                     (set! column (+ column 1)))
                    (append-rectangle! (+ glyph-x run-start)
                                       (+ glyph-y row)
                                       (- column run-start)
                                       1
                                       palette))
                  (set! column (+ column 1)))))
          (set! row (+ row 1))))
        (set! cursor (+ cursor (az/field (az/deref character) xadvance)))
        (set! index (+ index (utf8-width-at text index)))))))

(az/defn initialize!
  "Load three real fonts and cache the coco-factory HUD and controls."
  :-
  :bool
  []
  (when (ak/! initialized)
    (ak/memset (std-mem/asBytes (ak/& fonts)) 0)
    (ak/memset (std-mem/asBytes (ak/& rectangles)) 0)
    (ak/memset (std-mem/asBytes (ak/& dialogue-starts)) 0)
    (ak/memset (std-mem/asBytes (ak/& dialogue-counts)) 0)
    (set! rectangle-count 0)
    (let [sans (load-font! 0 "build/assets/fonts/source-sans.ttf" 24.0)
          serif (load-font! 1 "build/assets/fonts/source-serif.ttf" 24.0)
          mono (load-font! 2 "build/assets/fonts/source-code-pro.ttf" 22.0)]
      (when (and sans serif mono)
        ;; Static factory labels are cached once from the real TTF files. Text
        ;; is still rasterized once from the real TTF files, never rebuilt per
        ;; frame.
        (layout-text! 0 "COCO HOUSE WORKS" 18 40 4)
        (layout-text! 2 "COCOS" 250 20 5)
        (layout-text! 2 "PANELS" 360 20 6)
        (layout-text! 2 "HOUSES" 480 20 7)
        (layout-text! 2 "GOAL" 610 20 8)
        (layout-text! 0 "GOAL COMPLETE" 278 96 10)
        (layout-text! 2 "1 BELT" 24 482 11)
        (layout-text! 2 "2 HARVEST" 128 482 12)
        (layout-text! 2 "4 PRESS" 278 482 14)
        (layout-text! 2 "5 SPLIT" 388 482 15)
        (layout-text! 2 "6 HOUSE" 500 482 16)
        (layout-text! 2 "R ROTATE" 614 482 17)
        (set! initialized true))))
  initialized)

(az/defn reload!
  "Rebuild cached dialogue spans after a live text/font edit."
  :-
  :bool
  []
  (set! initialized false)
  (initialize!))

(az/defn rect-count
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  []
  (if (initialize!) rectangle-count 0))

(az/defn rect-at
  "Return one exact cached font span for rendering or nREPL inspection."
  :-
  FontRect
  [[index :usize]]
  (if (and (initialize!) (< index rectangle-count))
    (az/index rectangles index)
    (FontRect {:x 0 :y 0 :width 0 :height 0 :palette 0})))

(az/defn dialogue-start
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[dialogue :u8]]
  (if (and (initialize!) (> dialogue 0) (< dialogue 5))
    (az/index dialogue-starts dialogue)
    0))

(az/defn dialogue-rect-count
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[dialogue :u8]]
  (if (and (initialize!) (> dialogue 0) (< dialogue 5))
    (az/index dialogue-counts dialogue)
    0))

(az/defn snapshot
  "Inspect actual font loading and cached layout state."
  :-
  FontSnapshot
  []
  (let [^{:var true :zig/type :u32} loaded 0]
    (dotimes [index font-count]
      (when (az/field (az/index fonts index) ready)
        (set! loaded (+ loaded 1))))
    (FontSnapshot
     {:initialized initialized
      :loaded_fonts loaded
      :rectangles (ak/intCast rectangle-count)
      :capacity (ak/intCast rectangle-capacity)})))
