(ns simple-game.hud-mesh
  "Batch the native HUD into the existing mapped Vulkan triangle buffer."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [simple-game.hud :as hud]
            [simple-game.mesh :as mesh]))

(az/defvar active-output [:optional [:c-pointer mesh/GpuVertex]] null)

(az/defvar active-count :usize 0)

(az/defvar active-width :f32 1.0)

(az/defvar active-height :f32 1.0)

(az/defvar active-depth :f32 0.019)

(az/defn write-screen-vertex! :void
  [[output [:c-pointer mesh/GpuVertex]]
   [index :usize]
   [x :f32]
   [y :f32]
   [depth :f32]
   [color hud/Color]]
  (set! (az/index output index)
        (mesh/GpuVertex
         {:x (- (/ (* x 2.0) active-width) 1.0)
          :y (- (/ (* y 2.0) active-height) 1.0)
          :z depth
          :r (az/field color r)
          :g (az/field color g)
          :b (az/field color b)})))

(az/defn append-screen-rect! :usize
  [[output [:c-pointer mesh/GpuVertex]]
   [output-count :usize]
   [color hud/Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]
   [depth :f32]]
  (if (> (+ output-count 6) mesh/frame-capacity)
    output-count
    (let [left (ak/as (ak/floatFromInt x) :f32)
          top (ak/as (ak/floatFromInt y) :f32)
          right (ak/as (ak/floatFromInt (+ x width)) :f32)
          bottom (ak/as (ak/floatFromInt (+ y height)) :f32)]
      (write-screen-vertex! output output-count left top depth color)
      (write-screen-vertex! output (+ output-count 1) right top depth color)
      (write-screen-vertex! output (+ output-count 2) right bottom depth color)
      (write-screen-vertex! output (+ output-count 3) left top depth color)
      (write-screen-vertex! output (+ output-count 4) right bottom depth color)
      (write-screen-vertex! output (+ output-count 5) left bottom depth color)
      (+ output-count 6))))

(az/defn capture-rect! :void
  "HUD callback that appends six vertices instead of recording a Vulkan call."
  [[color hud/Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]]
  (when (ak/!= active-output null)
    (set! active-count
          (append-screen-rect! (az/unwrap active-output) active-count
                               color x y width height active-depth))
    (set! active-depth (- active-depth 0.000001))))

(az/defn append-overlay! :u32
  "Append the full HUD after the 3D mesh and return the combined vertex count."
  [[output [:c-pointer mesh/GpuVertex]]
   [output-count :u32]
   [frame-width :i32]
   [frame-height :i32]]
  (set! active-output output)
  (set! active-count (ak/intCast output-count))
  (set! active-width (ak/as (ak/floatFromInt frame-width) :f32))
  (set! active-height (ak/as (ak/floatFromInt frame-height) :f32))
  (set! active-depth 0.019)
  (hud/draw-overlay (ak/& capture-rect!) frame-width frame-height)
  (set! active-output null)
  (ak/intCast active-count))
