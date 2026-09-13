(ns field-lab.panel
  "Pitoco's native UI and export policy, compiled from the AguaFria Zig DSL."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.std.heap :as heap]
            [aguafria.std.math :as math]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.runtime :as stdio]
            [aguafria-examples-native.imgui-controls]
            [aguafria-examples-native.bindings.imgui-controls :as ui]
            [clojure.java.io :as io]))

(let [header (io/file "native/panel.h")
      output (io/file "generated/field_lab/panel_api.clj")]
  (ac/translate-header! header output {:namespace 'field-lab.panel-api :overwrite? true})
  (ac/load-bindings! output))

(require '[field-lab.panel-api :as api])

(az/defextern fprintf
  {:zig/prefix "pub extern"}
  :- :c_int
  [[file [:optional [:* stdio/AguafriaFile]]] [format [:pointer {:size :c :const? true} :u8]]
   [... {:zig/variadic true} _]])

(az/defextern snprintf
  {:zig/prefix "pub extern"}
  :- :c_int
  [[buffer [:c-pointer :u8]] [size :usize] [format [:pointer {:size :c :const? true} :u8]]
   [... {:zig/variadic true} _]])

(az/defextern mkdir
  {:zig/prefix "pub extern"}
  :- :c_int [[path [:pointer {:size :c :const? true} :u8]] [mode :u16]])

(az/defextern remove
  {:zig/prefix "pub extern"}
  :- :c_int [[path [:pointer {:size :c :const? true} :u8]]])

(az/defn text!
  :- :void [[text [:slice-const :u8]]]
  (ui/aguafria_ui_text (az/field text ptr) (az/field text len) 0.87 0.91 0.94))

(az/defn label!
  :- :void [[text [:slice-const :u8]]]
  (ui/aguafria_ui_text (az/field text ptr) (az/field text len) 0.47 0.54 0.61))

(az/defn colored!
  :- :void [[text [:slice-const :u8]] [r :f32] [g :f32] [b :f32]]
  (ui/aguafria_ui_text (az/field text ptr) (az/field text len) r g b))

(az/defn wrapped!
  :- :void [[text [:slice-const :u8]]]
  (ui/aguafria_ui_wrapped_text (az/field text ptr) (az/field text len) 0.87 0.91 0.94))

(defmacro textf!
  "Bounded formatting into stack storage; never interprets user text as a format."
  [format & arguments]
  `(~'let [^{:var [:array 512 :u8]} buffer# ak/undefined
         count# (snprintf (ak/& (az/index buffer# 0)) 512 ~format ~@arguments)]
     (~'when (~'and (~'>= count# 0) (~'< count# 512))
       (text! (az/slice buffer# 0 (ak/as :usize (ak/intCast count#)))))))

(az/defn number!
  :- :void
  [[name [:pointer {:size :c :const? true} :u8]] [value [:c-pointer :f64]]
   [low :f64] [high :f64] [format [:pointer {:size :c :const? true} :u8]]]
  (ui/aguafria_ui_item_width 142)
  (set! _ (ui/aguafria_ui_slider_double name value low high format)))

(az/defn metric!
  :- :void [[title [:slice-const :u8]] [value :f64] [unit [:pointer {:size :c :const? true} :u8]]]
  (label! title)
  (textf! "%.3f %s" value unit)
  (ui/aguafria_ui_spacing))

(az/defn pressed?
  :- :bool [[key :c_int]]
  (ak/!= (ui/aguafria_ui_key key) 0))

(az/defn flag
  :- :u8 [[value :bool]]
  (if value 1 0))

(az/defn rgba
  :- :u32 [[r :u32] [g :u32] [b :u32]]
  (ak/| r (ak/<< g 8) (ak/<< b 16) 4278190080))

(az/defn node!
  :- :void
  [[title [:pointer {:size :c :const? true} :u8]] [subtitle [:pointer {:size :c :const? true} :u8]]
   [id :i32] [panel [:* api/LabPanel]] [x :f32] [y :f32]]
  (let [selected (ak/== (az/field panel selected) id)
        accent (rgba 65 213 180)]
    (ui/aguafria_ui_draw_rect x y 182 66 (rgba 30 39 48) 6 0 1)
    (ui/aguafria_ui_draw_rect x y 182 66 (if selected accent (rgba 62 75 86)) 6 (if selected 2.0 1.0) 0)
    (ui/aguafria_ui_draw_rect x y 4 66 accent 3 0 1)
    (ui/aguafria_ui_draw_text (+ x 15) (+ y 12) (rgba 224 233 240) title)
    (ui/aguafria_ui_draw_text (+ x 15) (+ y 36) (rgba 135 157 173) subtitle)
    (ui/aguafria_ui_draw_circle x (+ y 33) 4 accent)
    (ui/aguafria_ui_draw_circle (+ x 182) (+ y 33) 4 accent)
    (ui/aguafria_ui_cursor x y 1)
    (when (ak/!= (ui/aguafria_ui_invisible_button title 182 66) 0)
      (set! (az/field panel selected) id))))

(az/defn input!
  :- :void [[panel [:* api/LabPanel]] [input ui/AguafriaUIInput]]
  (set! (az/field panel action) 0)
  (when (ak/== (az/field input text_input) 0)
    (when (and (pressed? 32) (ak/== (az/field panel baking) 0))
      (set! (az/field panel paused) (if (ak/== (az/field panel paused) 0) 1 0)))
    (when (pressed? 82) (set! (az/field panel action) 1))
    (when (or (pressed? 49) (pressed? 51))
      (az/set-many! (az/field panel mode) (if (pressed? 51) 3 1)
                    (az/field panel action) 5))
    (when (pressed? 68)
      (az/set-many! (az/field panel deform) (ak/mod (+ (az/field panel deform) 1) 3)
                    (az/field panel action) 6))
    (when (and (pressed? 27) (ak/!= (az/field panel baking) 0))
      (set! (az/field panel action) 8))
    (when (ak/== (az/field panel baking) 0)
      (when (pressed? 69) (set! (az/field panel action) 4))
      (when (or (pressed? 257) (pressed? 258))
        (az/set-many! (az/field panel cursor)
                      (ak/max 0 (ak/min (ak/max 0 (- (az/field panel count) 1))
                                        (+ (az/field panel cursor) (if (pressed? 258) 1 (ak/as :i32 -1)))))
                      (az/field panel paused) 1
                      (az/field panel action) 3))
      (when (pressed? 256)
        (az/set-many! (az/field panel cursor) 0
                      (az/field panel paused) 1
                      (az/field panel action) 3))))
  (when (and (> (az/field input mouse_x) 225) (< (az/field input mouse_x) (- (az/field input width) 305))
             (> (az/field input mouse_y) 66) (< (az/field input mouse_y) (- (az/field input height) 226)))
    (when (ak/!= (az/field input right_drag) 0)
      (az/set-many! (az/field panel yaw) (- (az/field panel yaw) (* (az/field input delta_x) 0.006))
                    (az/field panel pitch) (ak/max 0.08 (ak/min 1.3 (+ (az/field panel pitch) (* (az/field input delta_y) 0.005))))))
    (set! (az/field panel distance) (ak/max 4.0 (ak/min 24.0 (- (az/field panel distance) (* (az/field input wheel) 0.6)))))))

(az/defn style!
  :- :void []
  (ui/aguafria_ui_disable_ini)
  (ui/aguafria_ui_style_vector 0 18 16)
  (ui/aguafria_ui_style_vector 1 10 9)
  (ui/aguafria_ui_style_vector 2 8 5)
  (ui/aguafria_ui_style_scalar 0 0)
  (ui/aguafria_ui_style_scalar 1 4)
  (ui/aguafria_ui_style_color 0 0.065 0.083 0.105 1)
  (ui/aguafria_ui_style_color 1 0.87 0.91 0.94 1)
  (ui/aguafria_ui_style_color 2 0.12 0.23 0.25 1)
  (ui/aguafria_ui_style_color 3 0.16 0.37 0.36 1)
  (ui/aguafria_ui_style_color 4 0.12 0.16 0.20 1)
  (ui/aguafria_ui_style_color 5 0.24 0.84 0.72 1))

(az/defvar authored-jobs-visible :bool false)

(az/defn header!
  :- :void [[panel [:* api/LabPanel]] [width :f32] [scripted :bool]]
  (ui/aguafria_ui_panel_begin "brand" 0 0 width 66)
  (colored! "P / T" 0.24 0.84 0.72)
  (ui/aguafria_ui_same_line)
  (text! "PITOCO")
  (ui/aguafria_ui_same_line_at 235)
  (label! (if scripted "/ scripted solid scene" "/ sphere-impact / experiment_001"))
  (ui/aguafria_ui_same_line_at (- width 278))
  (colored! "FLECS" 0.24 0.84 0.72)
  (ui/aguafria_ui_same_line)
  (label! " |  VULKAN  |  SI")
  (ui/aguafria_ui_cursor 498 24 0)
  (when (ak/!= (ui/aguafria_ui_radio "Single ball" (flag (and (ak/== (az/field panel mode) 1) (ak/! scripted)))) 0)
    (az/set-many! (az/field panel mode) 1 (az/field panel action) 5))
  (ui/aguafria_ui_same_line)
  (when (ak/!= (ui/aguafria_ui_radio "Three balls" (flag (and (ak/== (az/field panel mode) 3) (ak/! scripted)))) 0)
    (az/set-many! (az/field panel mode) 3 (az/field panel action) 5))
  (ui/aguafria_ui_cursor 785 24 0)
  (when (ak/!= (ui/aguafria_ui_button "Authored jobs") 0)
    (set! authored-jobs-visible (ak/! authored-jobs-visible)))
  (ui/aguafria_ui_window_end))

(az/defconst model-items [:array 26 :u8]
  (az/array-init [:array 26 :u8] [82 105 103 105 100 0 88 80 66 68 0 67 111 110 116 105 110 117 117 109 32 70 69 77 0 0]))

(az/defn explorer!
  :- :void [[panel [:* api/LabPanel]] [height :f32] [nodes :i32] [tets :i32] [scripted :bool]]
  (ui/aguafria_ui_panel_begin "scene" 0 66 225 (- height 292))
  (label! "SCENE EXPLORER")
  (ui/aguafria_ui_separator)
  (ui/aguafria_ui_spacing)
  (text! (if scripted "v  scripted scene" "v  sphere-impact"))
  (let [names (az/array-init [:array 3 [:pointer {:size :c :const? true} :u8]]
                 [(if scripted "   mesh_sources" "   sphere_source") "   mechanical_solver" "   telemetry_output"])]
    (dotimes [index 3]
      (when (ak/!= (ui/aguafria_ui_selectable (az/index names index)
                      (flag (ak/== (az/field panel selected) (ak/as :i32 (ak/intCast index))))) 0)
        (set! (az/field panel selected) (ak/intCast index)))))
  (ui/aguafria_ui_spacing)
  (ui/aguafria_ui_separator)
  (label! "WORLD")
  (text! "Y up / metres")
  (wrapped! (if scripted "Per-body gravity / floor settings" "Infinite ground plane"))
  (ui/aguafria_ui_spacing)
  (label! "SOLVER")
  (text! (if (ak/== (az/field panel deform) 2) "Continuum FEM / f64"
           (if (ak/!= (az/field panel deform) 0) "Deformable XPBD / f64" "Rigid body / f64")))
  (text! "240 Hz / offline bake")
  (if (> nodes 0)
    (do (textf! "%d nodes / %d tets" nodes tets) (wrapped! "Refined cache / Clojure job"))
    (text! (if (ak/!= (az/field panel deform) 0) "43 nodes / 80 tetrahedra" "Sphere-plane CCD")))
  (ui/aguafria_ui_spacing)
  (ui/aguafria_ui_item_width 190)
  (when (ak/!= (ui/aguafria_ui_combo "##material_solver" (ak/& (az/field panel deform)) (ak/& (az/index model-items 0))) 0)
    (set! (az/field panel action) 6))
  (label! "AUTHORITATIVE STORE")
  (text! "Flecs components")
  (text! (if scripted "ScriptedScene / mesh caches" "BallConfig / BallState"))
  (textf! "Revision %d" (az/field panel revision))
  (when (ak/== nodes 0)
    (ui/aguafria_ui_cursor_y_set (ak/max (+ (ui/aguafria_ui_cursor_y) 8) (- height 364)))
    (label! "VIEWPORT")
    (text! "Drag R: orbit / Wheel: zoom")
    (text! "1 / 3: source  D: material"))
  (ui/aguafria_ui_window_end))

(az/defn parameters!
  :- :void
  [[panel [:* api/LabPanel]] [width :f32] [height :f32]
   [nodes :i32] [capture-status :i32] [scripted :bool]]
  (ui/aguafria_ui_panel_begin "parameters" (- width 305) 66 305 (- height 292))
  (label! (if (ak/== (az/field panel selected) 0) (if scripted "AUTHORED SOLIDS" "SPHERE SOURCE")
             (if (ak/== (az/field panel selected) 1) "MECHANICAL SOLVER" "TELEMETRY OUTPUT")))
  (ui/aguafria_ui_separator)
  (ui/aguafria_ui_disabled_begin (flag (ak/!= nodes 0)))
  (cond
    (and scripted (ak/!= (az/field panel selected) 2))
    (do
      (textf! "%d authored solids" (az/field panel mode))
      (wrapped! "Meshes, materials, density, gravity and initial state come from the scene data.")
      (textf! "First body mass: %.6g kg" (az/field panel mass))
      (textf! "First body modulus: %.6g Pa" (az/field panel stiffness))
      (wrapped! "Export includes per-body metadata and the saved scene source."))

    (ak/== (az/field panel selected) 0)
    (do
      (number! "Radius" (ak/& (az/field panel radius)) 0.1 1.0 "%.2f m")
      (number! "Mass" (ak/& (az/field panel mass)) 0.05 20 "%.2f kg")
      (number! "Drop gap" (ak/& (az/field panel height)) 0.1 8 "%.2f m")
      (number! "Launch X" (ak/& (az/field panel vx)) -4 4 "%.2f m/s")
      (number! "Launch Z" (ak/& (az/field panel vz)) -4 4 "%.2f m/s")
      (number! "Spin Y" (ak/& (az/field panel spin)) -20 20 "%.2f rad/s"))

    (ak/== (az/field panel selected) 1)
    (do
      (number! "Gravity" (ak/& (az/field panel gravity)) 0 20 "%.3f m/s2")
      (number! "Friction" (ak/& (az/field panel friction)) 0 1 "%.3f")
      (if (ak/!= (az/field panel deform) 0)
        (do
          (number! (if (ak/== (az/field panel deform) 2) "Young modulus" "Stiffness")
                   (ak/& (az/field panel stiffness)) 1000 100000 "%.0f Pa")
          (wrapped! (if (ak/== (az/field panel deform) 2)
                      "Solid continuum, Poisson ratio 0.4; not calibrated."
                      "Elastic network + volume constraints. Effective stiffness; not calibrated.")))
        (do
          (number! "Restitution" (ak/& (az/field panel restitution)) 0 1 "%.3f")
          (number! "Rolling loss" (ak/& (az/field panel rolling)) 0 0.2 "%.3f")
          (wrapped! "Solid sphere: I = 2/5 mr^2. Coulomb contact impulses couple translation and spin."))))

    :else
    (do
      (metric! "SYSTEM MECHANICAL ENERGY" (az/field panel energy) "J")
      (metric! "KINETIC ENERGY" (az/field panel kinetic) "J")
      (if (ak/!= (az/field panel deform) 0)
        (metric! "VOLUME / REST VOLUME" (az/field panel volume_ratio) "")
        (metric! "NORMAL IMPULSE / TICK" (az/field panel impulse) "N s"))
      (textf! "Position [m]\n%.3f, %.3f, %.3f" (az/field panel px) (az/field panel py) (az/field panel pz))))
  (ui/aguafria_ui_disabled_end)
  (ui/aguafria_ui_spacing)
  (if (ak/!= nodes 0)
    (wrapped! "Refined job cache. Source and material selectors return to coarse experiments.")
    (do
      (when (ak/!= (ui/aguafria_ui_button_size "Apply & bake experiment" -1 30) 0)
        (set! (az/field panel action) 1))
      (label! "Edits apply when baking.")
      (ui/aguafria_ui_separator)
      (number! "Duration" (ak/& (az/field panel duration)) 0.25 60 "%.2f s")))
  (metric! (if (ak/!= (az/field panel baking) 0) "BAKING SIMULATION TIME" "CACHED SIMULATION TIME")
           (az/field panel time) "s")
  (textf! "Speed %.3f m/s" (az/field panel speed))
  (if (ak/!= (az/field panel deform) 0)
    (textf! "%s %.1f%% / min Y %.3f m" (ak/as (az/type [:pointer {:size :c :const? true} :u8]) (if scripted "Height decrease" "Compression"))
            (* (az/field panel compression) 100) (az/field panel clearance))
    (textf! "Impacts  %d" (az/field panel impacts)))
  (textf! "Cache    %d / 14401" (az/field panel count))
  (when (ak/!= (ui/aguafria_ui_button_size "Export cached run (.csv)" -1 28) 0)
    (set! (az/field panel action) 4))
  (when (ak/!= (az/field panel exported) 0)
    (if (> (az/field panel exported) 0)
      (colored! "Saved exports/trajectory.csv" 0.24 0.84 0.72)
      (colored! (if (ak/== (az/field panel exported) -2) "FEM solve stopped; partial cache retained" "Export failed") 1 0.4 0.3)))
  (when (>= capture-status 0)
    (ui/aguafria_ui_disabled_begin (flag (and (> capture-status 0) (< capture-status 4))))
    (when (ak/!= (ui/aguafria_ui_button_size "Capture rendered frame" -1 28) 0)
      (set! _ (mkdir "exports" 493))
      (set! (az/field panel action) 9))
    (when (ak/!= (ui/aguafria_ui_hovered) 0)
      (ui/aguafria_ui_tooltip "Save the next rendered frame and its simulation tick to exports/frame.ppm"))
    (ui/aguafria_ui_disabled_end)
    (when (ak/== capture-status 4) (colored! "Rendered frame saved" 0.24 0.84 0.72))
    (when (>= capture-status 5)
      (colored! (if (ak/== capture-status 6) "Frame file could not be saved" "Frame readback unavailable") 1 0.4 0.3)))
  (ui/aguafria_ui_window_end))

(az/defn graph!
  :- :void [[panel [:* api/LabPanel]] [width :f32] [height :f32] [nodes :i32] [scripted :bool]]
  (ui/aguafria_ui_panel_begin "graph" 0 (- height 226) width 162)
  (label! "PROCEDURAL NETWORK")
  (ui/aguafria_ui_same_line)
  (colored! "  /  live Flecs dependencies" 0.24 0.84 0.72)
  (let [y (- height 169)]
    (dotimes [index 2]
      (let [a (+ 214 (* (ak/as :f32 (ak/floatFromInt index)) 242))]
        (ui/aguafria_ui_draw_bezier a (+ y 33) (+ a 28) (+ y 33) (+ a 30) (+ y 33)
                                   (+ a 60) (+ y 33) (rgba 65 150 141) 2)))
    (node! (if scripted "mesh_sources" "sphere_source") "Geometry + initial state" 0 panel 32 y)
    (node! "mechanical_solver" (if (ak/== (az/field panel deform) 2) "FEM / Neo-Hookean"
                                  (if (ak/!= (az/field panel deform) 0) "XPBD / elastic / volume" "CCD / friction / spin"))
           1 panel 274 y)
    (node! "telemetry_output" "Cache + SI observables" 2 panel 516 y)
    (ui/aguafria_ui_cursor (- width 330) (+ y 8) 1)
    (label! "NUMERICAL CONTRACT")
    (ui/aguafria_ui_cursor (- width 330) (+ y 31) 1)
    (text! "Bake first. Play measured cache.")
    (ui/aguafria_ui_cursor (- width 330) (+ y 53) 1)
    (label! (if (ak/!= nodes 0)
              (if (> (az/field panel mode) 1) "FEM / triangle contact + friction" "FEM / nodal plane contact")
              (if (ak/== (az/field panel deform) 2) "FEM / coarse convex contact"
                (if (ak/!= (az/field panel deform) 0) "XPBD elastic network" "Rigid contact dynamics")))))
  (ui/aguafria_ui_window_end))

(az/defn transport!
  :- :void [[panel [:* api/LabPanel]] [width :f32] [height :f32] [nodes :i32]]
  (ui/aguafria_ui_panel_begin "transport" 0 (- height 64) width 64)
  (ui/aguafria_ui_disabled_begin (flag (ak/!= nodes 0)))
  (when (ak/!= (ui/aguafria_ui_button (if (ak/!= (az/field panel baking) 0) "STOP BAKE" "BAKE")) 0)
    (set! (az/field panel action) (if (ak/!= (az/field panel baking) 0) 8 1)))
  (ui/aguafria_ui_disabled_end)
  (ui/aguafria_ui_same_line)
  (ui/aguafria_ui_disabled_begin (flag (or (ak/!= (az/field panel baking) 0) (<= (az/field panel count) 1))))
  (when (ak/!= (ui/aguafria_ui_button (if (ak/!= (az/field panel paused) 0) "PLAY CACHE" "PAUSE")) 0)
    (when (and (ak/!= (az/field panel paused) 0) (>= (az/field panel cursor) (- (az/field panel count) 1)))
      (az/set-many! (az/field panel cursor) 0 (az/field panel action) 3))
    (set! (az/field panel paused) (if (ak/== (az/field panel paused) 0) 1 0)))
  (ui/aguafria_ui_same_line)
  (when (ak/!= (ui/aguafria_ui_button "STEP") 0)
    (az/set-many! (az/field panel paused) 1 (az/field panel action) 2))
  (ui/aguafria_ui_same_line)
  (when (ak/!= (ui/aguafria_ui_button "REWIND") 0)
    (az/set-many! (az/field panel paused) 1 (az/field panel cursor) 0 (az/field panel action) 3))
  (ui/aguafria_ui_same_line)
  (ui/aguafria_ui_item_width (- width 700))
  (let [^:var tick (az/field panel cursor)]
    (when (ak/!= (ui/aguafria_ui_slider_int "##cache" (ak/& tick) 0 (ak/max 0 (- (az/field panel count) 1)) "Tick %d") 0)
      (az/set-many! (az/field panel cursor) tick (az/field panel paused) 1 (az/field panel action) 3)))
  (ui/aguafria_ui_same_line)
  (ui/aguafria_ui_item_width 105)
  (set! _ (ui/aguafria_ui_slider_float "Rate" (ak/& (az/field panel rate)) 0.1 2.0 "%.2fx"))
  (ui/aguafria_ui_same_line)
  (let [^:var loop (flag (ak/!= (az/field panel loop) 0))]
    (when (ak/!= (ui/aguafria_ui_checkbox "Loop" (ak/& loop)) 0)
      (set! (az/field panel loop) loop)))
  (ui/aguafria_ui_disabled_end)
  (ui/aguafria_ui_window_end))

(az/defn render!
  :- :void
  [[command :u64] [panel [:* api/LabPanel]] [nodes :i32] [tets :i32]
   [capture-status :i32] [scripted :bool] [overlay [:*const [:fn {:callconv :.c} [] :void]]]]
  (ui/aguafria_ui_window_title "Pitoco - AguaFria")
  (ui/aguafria_ui_frame_begin)
  (style!)
  (let [^:var input (mem/zeroes (az/type ui/AguafriaUIInput))]
    (ui/aguafria_ui_input (ak/& input))
    (input! panel input)
    (header! panel (az/field input width) scripted)
    (explorer! panel (az/field input height) nodes tets scripted)
    (parameters! panel (az/field input width) (az/field input height) nodes capture-status scripted)
    (ui/aguafria_ui_background_alpha 0)
    (ui/aguafria_ui_panel_begin "view label" 239 74 440 68)
    (let [baking (ak/!= (az/field panel baking) 0)]
      (colored! (if (ak/== (az/field panel exported) -2) "SOLVER FAILED / PARTIAL CACHE"
                   (if baking "BAKING / SOLVER TIME INDEPENDENT OF DISPLAY"
                     (if (ak/!= (az/field panel paused) 0) "CACHE PAUSED / SPACE TO PLAY" "CACHE PLAYBACK / MEASURED SIMULATION")))
                (if baking 0.24 1.0) (if baking 0.84 0.72) (if baking 0.72 0.25)))
    (label! (if (ak/!= (az/field panel deform) 0) "01 / Simulated elastic tetrahedral surface" "01 / Rigid sphere contact study"))
    (ui/aguafria_ui_window_end)
    (graph! panel (az/field input width) (az/field input height) nodes scripted)
    (transport! panel (az/field input width) (az/field input height) nodes)
    (when (and (ak/!= nodes 0) (ak/== (az/field panel action) 1))
      (set! (az/field panel action) 0)))
  (overlay)
  (ui/aguafria_ui_frame_render command))

;; Files remain native and independent of any JVM scripting connection.
(az/defstruct Export
  [[:trajectory [:optional [:* stdio/AguafriaFile]]]
   [:particles [:optional [:* stdio/AguafriaFile]]]
   [:reference [:optional [:* stdio/AguafriaFile]]]
   [:cells [:optional [:* stdio/AguafriaFile]]]
   [:metadata [:optional [:* stdio/AguafriaFile]]]
   [:deform :bool]])

(az/defn export-begin!
  :- [:optional [:* :anyopaque]]
  [[radius :f64] [mass :f64] [height :f64] [gravity :f64] [restitution :f64]
   [friction :f64] [rolling :f64] [vx :f64] [vz :f64] [spin :f64]
   [bodies :i32] [dt :f64] [deform :i32] [stiffness :f64]]
  (set! _ (mkdir "exports" 493))
  ;; Do not let a coarse export retain topology from a previously authored job.
  (set! _ (remove "exports/reference.csv"))
  (set! _ (remove "exports/cells.csv"))
  (let [metadata (stdio/fopen "exports/experiment.json" "w")]
    (when (ak/== metadata null) (ak/return null))
    (let [written (fprintf metadata
                    "{\n  \"solver\": \"%s\",\n  \"%s\": %.17g,\n  \"poisson_ratio\": %s,\n  \"body_count\": %d,\n  \"dt_s\": %.17g,\n  \"radius_m\": %.17g,\n  \"mass_kg\": %.17g,\n  \"drop_gap_m\": %.17g,\n  \"gravity_m_s2\": %.17g,\n  \"restitution\": %.17g,\n  \"friction\": %.17g,\n  \"rolling\": %.17g,\n  \"launch_x_m_s\": %.17g,\n  \"launch_z_m_s\": %.17g,\n  \"spin_y_rad_s\": %.17g\n}\n"
                    (ak/as (az/type [:pointer {:size :c :const? true} :u8])
                      (if (ak/== deform 2) "field-lab-stable-neo-hookean-v1"
                        (if (ak/!= deform 0) "field-lab-xpbd-v1" "field-lab-rigid-v1")))
                    (ak/as (az/type [:pointer {:size :c :const? true} :u8])
                      (if (ak/== deform 2) "young_modulus_Pa" "effective_stiffness_Pa"))
                    stiffness
                    (ak/as (az/type [:pointer {:size :c :const? true} :u8]) (if (ak/== deform 2) "0.4" "null"))
                    bodies dt radius mass height gravity restitution friction rolling vx vz spin)
          closed (stdio/fclose metadata)]
      (when (or (< written 0) (ak/!= closed 0)) (ak/return null))))
  (let [run (catch ((az/field heap/page_allocator create) Export) (ak/return null))
        ^:var success false]
    (set! (az/deref run) (mem/zeroes (az/type Export)))
    (defer (when (ak/! success)
             (when (ak/!= (az/field run trajectory) null) (set! _ (stdio/fclose (az/field run trajectory))))
             (when (ak/!= (az/field run particles) null) (set! _ (stdio/fclose (az/field run particles))))
             ((az/field heap/page_allocator destroy) run)))
    (az/set-many! (az/field run deform) (ak/!= deform 0)
                  (az/field run trajectory) (stdio/fopen "exports/trajectory.csv" "w"))
    (when (or (ak/== (az/field run trajectory) null)
              (< (fprintf (az/field run trajectory)
                   "body,time_s,x_m,y_m,z_m,vx_m_s,vy_m_s,vz_m_s,wx_rad_s,wy_rad_s,wz_rad_s,qx,qy,qz,qw,energy_J,normal_impulse_N_s\n") 0))
      (ak/return null))
    (when (ak/!= deform 0)
      (set! (az/field run particles) (stdio/fopen "exports/particles.csv" "w"))
      (when (or (ak/== (az/field run particles) null)
                (< (fprintf (az/field run particles) "body,particle,time_s,x_m,y_m,z_m,vx_m_s,vy_m_s,vz_m_s\n") 0))
        (ak/return null)))
    (set! success true)
    (ak/ptrCast run)))

(az/defn export-handle
  :- [:* Export] [[file [:optional [:* :anyopaque]]]]
  (ak/ptrCast (ak/alignCast (az/unwrap file))))

(az/defn export-sample!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [body :i32] [time :f64] [x :f64] [y :f64] [z :f64]
   [vx :f64] [vy :f64] [vz :f64] [wx :f64] [wy :f64] [wz :f64]
   [qx :f64] [qy :f64] [qz :f64] [qw :f64] [energy :f64] [impulse :f64]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)
        deform (az/field run deform)
        nan (math/nan :f64)]
    (flag (>= (fprintf (az/field run trajectory)
                "%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g\n"
                body time x y z vx vy vz
                (if deform nan wx) (if deform nan wy) (if deform nan wz)
                (if deform nan qx) (if deform nan qy) (if deform nan qz) (if deform nan qw)
                energy (if deform nan impulse)) 0))))

(az/defn export-particle!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [body :i32] [particle :i32]
   [time :f64] [x :f64] [y :f64] [z :f64] [vx :f64] [vy :f64] [vz :f64]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)]
    (flag (and (ak/!= (az/field run particles) null)
               (>= (fprintf (az/field run particles) "%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g\n"
                             body particle time x y z vx vy vz) 0)))))

(az/defn export-reference!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [body :i32] [node :i32] [x :f64] [y :f64] [z :f64]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)]
    (when (ak/== (az/field run reference) null)
      (set! (az/field run reference) (stdio/fopen "exports/reference.csv" "w"))
      (when (or (ak/== (az/field run reference) null)
                (< (fprintf (az/field run reference) "body,node,x_m,y_m,z_m\n") 0)) (ak/return 0)))
    (flag (>= (fprintf (az/field run reference) "%d,%d,%.17g,%.17g,%.17g\n" body node x y z) 0))))

(az/defn export-cell!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [body :i32] [cell :i32] [a :i32] [b :i32] [c :i32] [d :i32]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)]
    (when (ak/== (az/field run cells) null)
      (set! (az/field run cells) (stdio/fopen "exports/cells.csv" "w"))
      (when (or (ak/== (az/field run cells) null)
                (< (fprintf (az/field run cells) "body,cell,a,b,c,d\n") 0)) (ak/return 0)))
    (flag (>= (fprintf (az/field run cells) "%d,%d,%d,%d,%d,%d\n" body cell a b c d) 0))))

(az/defn export-scene-source!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [source [:pointer {:size :c :const? true} :u8]] [bodies :i32] [dt :f64]]
  (when (or (ak/== file null) (ak/== source null)) (ak/return 0))
  (dotimes [index 64]
    (let [character (az/index source index)]
      (when (ak/! (or (and (>= character 48) (<= character 57)) (and (>= character 97) (<= character 102))))
        (ak/return 0))))
  (when (ak/!= (az/index source 64) 0) (ak/return 0))
  (let [run (export-handle file)]
    (when (ak/!= (az/field run metadata) null) (ak/return 0))
    (set! (az/field run metadata) (stdio/fopen "exports/experiment.json" "w"))
    (flag (and (ak/!= (az/field run metadata) null)
               (>= (fprintf (az/field run metadata)
                     "{\n\"format\": \"pitoco/solid-scene-v1\",\n\"scene_source\": \"scenes/%s.edn\",\n\"body_count\": %d,\n\"dt_s\": %.17g,\n\"bodies\": [\n"
                     source bodies dt) 0)))))

(az/defn export-scene-body!
  :- :i32
  [[file [:optional [:* :anyopaque]]] [body :i32] [mass :f64] [young :f64] [poisson :f64]
   [gx :f64] [gy :f64] [gz :f64] [floor :i32] [friction :f64]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)]
    (flag (and (ak/!= (az/field run metadata) null)
               (>= (fprintf (az/field run metadata)
                     "%s{\"body\":%d,\"mass_kg\":%.17g,\"young_modulus_Pa\":%.17g,\"poisson_ratio\":%.17g,\"gravity_m_s2\":[%.17g,%.17g,%.17g],\"floor\":%s,\"friction\":%.17g}"
                     (ak/as (az/type [:pointer {:size :c :const? true} :u8]) (if (ak/!= body 0) ",\n" ""))
                     body mass young poisson gx gy gz
                     (ak/as (az/type [:pointer {:size :c :const? true} :u8]) (if (ak/!= floor 0) "true" "false")) friction) 0)))))

(az/defn export-end!
  :- :i32 [[file [:optional [:* :anyopaque]]]]
  (when (ak/== file null) (ak/return 0))
  (let [run (export-handle file)
        ^:var success true]
    (when (ak/!= (az/field run metadata) null)
      (when (< (fprintf (az/field run metadata) "\n]}\n") 0) (set! success false)))
    (let [files (az/array-init [:array 5 [:optional [:* stdio/AguafriaFile]]]
                  [(az/field run trajectory) (az/field run particles) (az/field run reference)
                   (az/field run cells) (az/field run metadata)])]
      (dotimes [index 5]
        (when (ak/!= (az/index files index) null)
          (when (ak/!= (stdio/fclose (az/index files index)) 0) (set! success false)))))
    ((az/field heap/page_allocator destroy) run)
    (flag success)))
