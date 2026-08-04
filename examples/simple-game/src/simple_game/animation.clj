(ns simple-game.animation
  "Hot-reloadable 2D sprite animation for packed spritesheets or frame lists."
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings]
            [simple-game.bindings.stdio :as stdio]))

(az/defconst file-capacity :usize 131072)

(az/defconst frame-capacity :usize 64)

(az/defconst span-capacity :usize 16384)

(az/defstruct SpriteSpan
  "One opaque horizontal color run from a source sprite."
  {:layout :extern}
  [[:x :u8]
   [:y :u8]
   [:width :u8]
   [:red :u8]
   [:green :u8]
   [:blue :u8]
   [:alpha :u8]])

(az/defstruct SpriteFrame
  "A frame view into the shared span buffer."
  {:layout :extern}
  [[:first_span :usize]
   [:span_count :usize]
   [:width :u8]
   [:height :u8]])

(az/defstruct AnimationClip
  "A timed frame range independent of its source authoring format."
  {:layout :extern}
  [[:first_frame :u16]
   [:frame_count :u16]
   [:fps :f32]
   [:loop :bool]])

(az/defstruct AnimationPlayer
  "Pure player value that can be stored in a component or advanced directly."
  {:layout :extern}
  [[:clip AnimationClip]
   [:elapsed_seconds :f32]
   [:speed :f32]
   [:playing :bool]])

(az/defstruct AnimationSnapshot
  "Inspectable state for the generated water-orb demo animation."
  {:layout :extern}
  [[:initialized :bool]
   [:frames :u16]
   [:spans :u32]
   [:width :u8]
   [:height :u8]
   [:current_frame :u16]
   [:elapsed_seconds :f32]
   [:fps :f32]
   [:playing :bool]])

(az/defvar file-data [:array 131072 :u8]
  (std-mem/zeroes (az/type [:array 131072 :u8])))

(az/defvar frames [:array 64 SpriteFrame]
  (std-mem/zeroes (az/type [:array 64 SpriteFrame])))

(az/defvar spans [:array 16384 SpriteSpan]
  (std-mem/zeroes (az/type [:array 16384 SpriteSpan])))

(az/defvar initialized false)

(az/defvar loaded-frame-count :u16 0)

(az/defvar loaded-span-count :usize 0)

(az/defvar loaded-width :u8 0)

(az/defvar loaded-height :u8 0)

(az/defvar demo-player AnimationPlayer
  (AnimationPlayer
   {:clip (AnimationClip
           {:first_frame 0 :frame_count 0 :fps 8.0 :loop true})
    :elapsed_seconds 0.0
    :speed 1.0
    :playing true}))

(az/defn read-u16-le
  {:export false :implicit-return true}
  :-
  :u16
  [[index :usize]]
  (ak/|
   (ak/as :u16 (az/index file-data index))
   (ak/<< (ak/as :u16 (az/index file-data (+ index 1))) 8)))

(az/defn frame-at-time
  "Select a frame deterministically. Looping wraps; one-shot clips clamp."
  {:attrs #{:public :implicit-return}}
  :-
  :u16
  [[seconds :f32]
   [fps :f32]
   [frame-count :u16]
   [loop :bool]]
  (if (or (ak/== frame-count 0) (<= fps 0.0))
    0
    (let [safe-seconds (ak/max seconds 0.0)
          absolute-frame
          (ak/as :usize (ak/intFromFloat (* safe-seconds fps)))
          count (ak/as :usize frame-count)]
      (ak/intCast
       (if loop
         (mod absolute-frame count)
         (ak/min absolute-frame (- count 1)))))))

(az/defn make-player
  "Construct a player for any contiguous frame range in the loaded pack."
  {:attrs #{:public :implicit-return}}
  :-
  AnimationPlayer
  [[first-frame :u16]
   [frame-count :u16]
   [fps :f32]
   [loop :bool]]
  (AnimationPlayer
   {:clip (AnimationClip
           {:first_frame first-frame
            :frame_count frame-count
            :fps fps
            :loop loop})
    :elapsed_seconds 0.0
    :speed 1.0
    :playing true}))

(az/defn advance
  "Return the next immutable player value for a delta in seconds."
  {:attrs #{:public :implicit-return}}
  :-
  AnimationPlayer
  [[player AnimationPlayer]
   [delta-seconds :f32]]
  (AnimationPlayer
   {:clip (az/field player clip)
    :elapsed_seconds
    (if (az/field player playing)
      (+ (az/field player elapsed_seconds)
         (* (ak/max delta-seconds 0.0) (az/field player speed)))
      (az/field player elapsed_seconds))
    :speed (az/field player speed)
    :playing (az/field player playing)}))

(az/defn player-frame
  "Return the absolute frame selected by a player."
  {:attrs #{:public :implicit-return}}
  :-
  :u16
  [[player AnimationPlayer]]
  (let [clip (az/field player clip)]
    (+ (az/field clip first_frame)
       (frame-at-time (az/field player elapsed_seconds)
                      (az/field clip fps)
                      (az/field clip frame_count)
                      (az/field clip loop)))))

(az/defn load-pack!
  "Load a compact animation produced from either a spritesheet or sprite list."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[path [:pointer {:size :c :const? true} :u8]]]
  (let [file (stdio/fopen path "rb")
        bytes (if (ak/== file null)
                0
                (let [read (stdio/fread
                            (ak/& (az/index file-data 0))
                            1 file-capacity file)]
                  (set! _ (stdio/fclose file))
                  read))
        ^{:var true :zig/type :usize} offset 8
        ^{:var true :zig/type :usize} decoded-spans 0
        ^{:var true :zig/type :u16} decoded-frames 0
        encoded-frames (if (>= bytes 8)
                         (ak/as :u16 (az/index file-data 5))
                         0)
        ^:var valid
        (and (>= bytes 8)
             (ak/== (az/index file-data 0) 65)
             (ak/== (az/index file-data 1) 71)
             (ak/== (az/index file-data 2) 65)
             (ak/== (az/index file-data 3) 78)
             (ak/== (az/index file-data 4) 1)
             (> encoded-frames 0)
             (<= encoded-frames frame-capacity)
             (> (az/index file-data 6) 0)
             (> (az/index file-data 7) 0))]
    (ak/while (and valid (< decoded-frames encoded-frames))
      (if (> (+ offset 2) bytes)
        (set! valid false)
        (let [count (ak/as :usize (read-u16-le offset))
              spans-start (+ offset 2)
              next-frame (+ spans-start (* count 7))]
          (if (or (> next-frame bytes)
                  (> (+ decoded-spans count) span-capacity))
            (set! valid false)
            (do
              (set! (az/index frames decoded-frames)
                    (SpriteFrame
                     {:first_span decoded-spans
                      :span_count count
                      :width (az/index file-data 6)
                      :height (az/index file-data 7)}))
              (dotimes [span-index count]
                (let [source (+ spans-start (* span-index 7))]
                  (set! (az/index spans (+ decoded-spans span-index))
                        (SpriteSpan
                         {:x (az/index file-data source)
                          :y (az/index file-data (+ source 1))
                          :width (az/index file-data (+ source 2))
                          :red (az/index file-data (+ source 3))
                          :green (az/index file-data (+ source 4))
                          :blue (az/index file-data (+ source 5))
                          :alpha (az/index file-data (+ source 6))}))))
              (set! decoded-spans (+ decoded-spans count))
              (set! decoded-frames (+ decoded-frames 1))
              (set! offset next-frame))))))
    (set! initialized (and valid (ak/== decoded-frames encoded-frames)))
    (if initialized
      (do
        (set! loaded-frame-count decoded-frames)
        (set! loaded-span-count decoded-spans)
        (set! loaded-width (az/index file-data 6))
        (set! loaded-height (az/index file-data 7))
        (set! demo-player (make-player 0 decoded-frames 8.0 true)))
      (do
        (set! loaded-frame-count 0)
        (set! loaded-span-count 0)
        (set! loaded-width 0)
        (set! loaded-height 0)))
    initialized))

(az/defn initialize!
  "Load the generated demo spritesheet once; repeated calls preserve playback."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (if initialized
    true
    (load-pack! "build/assets/sprites/water-orb-sheet.agan")))

(az/defn use-generated-sprite-list!
  "Switch to the equivalent pack authored from six independent sprite files."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (load-pack! "build/assets/sprites/water-orb-sprites.agan"))

(az/defn tick!
  "Advance the shared demo player while keeping the player itself inspectable."
  :-
  :void
  [[delta-seconds :f32]]
  (when (initialize!)
    (set! demo-player (advance demo-player delta-seconds))))

(az/defn set-fps!
  "Change the live demo playback rate without reloading its sprites."
  :-
  :void
  [[fps :f32]]
  (set! _ (initialize!))
  (let [clip (az/field demo-player clip)]
    (set! demo-player
          (AnimationPlayer
           {:clip (AnimationClip
                   {:first_frame (az/field clip first_frame)
                    :frame_count (az/field clip frame_count)
                    :fps (ak/max fps 0.0)
                    :loop (az/field clip loop)})
            :elapsed_seconds (az/field demo-player elapsed_seconds)
            :speed (az/field demo-player speed)
            :playing (az/field demo-player playing)}))))

(az/defn set-playing!
  :-
  :void
  [[playing :bool]]
  (set! _ (initialize!))
  (set! demo-player
        (AnimationPlayer
         {:clip (az/field demo-player clip)
          :elapsed_seconds (az/field demo-player elapsed_seconds)
          :speed (az/field demo-player speed)
          :playing playing})))

(az/defn restart!
  :-
  :void
  []
  (set! _ (initialize!))
  (set! demo-player
        (AnimationPlayer
         {:clip (az/field demo-player clip)
          :elapsed_seconds 0.0
          :speed (az/field demo-player speed)
          :playing true})))

(az/defn current-frame
  {:attrs #{:public :implicit-return}}
  :-
  :u16
  []
  (if (initialize!) (player-frame demo-player) 0))

(az/defn frame-view
  "Inspect one loaded frame without copying its span storage."
  {:attrs #{:public :implicit-return}}
  :-
  SpriteFrame
  [[index :u16]]
  (if (and (initialize!) (< index loaded-frame-count))
    (az/index frames index)
    (SpriteFrame {:first_span 0 :span_count 0 :width 0 :height 0})))

(az/defn current-frame-view
  {:attrs #{:public :implicit-return}}
  :-
  SpriteFrame
  []
  (frame-view (current-frame)))

(az/defn span-at
  "Return one exact cached color run for rendering or nREPL inspection."
  {:attrs #{:public :implicit-return}}
  :-
  SpriteSpan
  [[index :usize]]
  (if (and (initialize!) (< index loaded-span-count))
    (az/index spans index)
    (SpriteSpan
     {:x 0 :y 0 :width 0 :red 0 :green 0 :blue 0 :alpha 0})))

(az/defn snapshot
  "Return loaded asset and playback state as a native value visible from Clojure."
  {:attrs #{:public :implicit-return}}
  :-
  AnimationSnapshot
  []
  (AnimationSnapshot
   {:initialized initialized
    :frames loaded-frame-count
    :spans (ak/intCast loaded-span-count)
    :width loaded-width
    :height loaded-height
    :current_frame (if initialized (player-frame demo-player) 0)
    :elapsed_seconds (az/field demo-player elapsed_seconds)
    :fps (az/field (az/field demo-player clip) fps)
    :playing (az/field demo-player playing)}))

(az/defn shutdown!
  :-
  :void
  []
  (set! initialized false)
  (set! loaded-frame-count 0)
  (set! loaded-span-count 0)
  (set! loaded-width 0)
  (set! loaded-height 0)
  (set! demo-player (make-player 0 0 8.0 true)))
