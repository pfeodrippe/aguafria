(ns racing-game.shaders
  "Mesh, instanced mesh and fragment stages for the native Vulkan renderer."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.spirv :as spv]
            [aguafria.zig :as a]))

(a/defconst clip-position
  (extern [:* {:addrspace :output} [:vector 4 :f32]] {:name "position"}))

(a/defconst in-position
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "in_position" :decoration {:location 0}}))

(a/defconst in-color
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "in_color" :decoration {:location 1}}))

(a/defconst in-normal
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "in_normal" :decoration {:location 2}}))

(a/defconst in-world
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "in_world" :decoration {:location 3}}))

(a/defconst in-roughness
  (extern [:* {:addrspace :input} :f32] {:name "in_roughness" :decoration {:location 4}}))

(a/defconst in-view-direction
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "in_view_direction" :decoration {:location 5}}))

;; Authored mesh uploaded once, shared by all cars. No CPU-expanded vertices.
(a/defconst position
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "instance_position" :decoration {:location 0}}))

(a/defconst normal
  (extern [:* {:addrspace :input} [:vector 3 :f32]] {:name "normal" :decoration {:location 1}}))

(a/defconst color
  (extern [:* {:addrspace :input} [:vector 3 :f32]] {:name "color" :decoration {:location 2}}))

(a/defconst tint-weight
  (extern [:* {:addrspace :input} :f32] {:name "tint_weight" :decoration {:location 3}}))

(a/defconst rotation
  (extern [:* {:addrspace :input} [:vector 4 :f32]]
          {:name "rotation" :decoration {:location 4}}))

(a/defconst translation-scale
  (extern [:* {:addrspace :input} [:vector 4 :f32]]
          {:name "translation_scale" :decoration {:location 5}}))

(a/defconst pivot-shadow
  (extern [:* {:addrspace :input} [:vector 4 :f32]]
          {:name "pivot_shadow" :decoration {:location 6}}))

(a/defconst tint-mode
  (extern [:* {:addrspace :input} [:vector 4 :f32]]
          {:name "tint_mode" :decoration {:location 7}}))

(a/defstruct Camera
  {:layout :extern}
  [[:position_zoom [:vector 4 :f32]]
   ;; cos(yaw), sin(yaw), cos(pitch), sin(pitch)
   [:angles [:vector 4 :f32]]
   [:fit [:vector 4 :f32]]])

(a/defconst camera
  (extern [:* {:addrspace :push_constant :const? true} Camera] {:name "camera"}))

(a/defconst vertex-color-out
  (extern [:* {:addrspace :output} [:vector 3 :f32]]
          {:name "vertex_color_out" :decoration {:location 0}}))

(a/defconst world-normal-out
  (extern [:* {:addrspace :output} [:vector 3 :f32]]
          {:name "world_normal_out" :decoration {:location 1}}))

(a/defconst world-position-out
  (extern [:* {:addrspace :output} [:vector 3 :f32]]
          {:name "world_position_out" :decoration {:location 2}}))

(a/defconst roughness-out
  (extern [:* {:addrspace :output} :f32] {:name "roughness_out" :decoration {:location 3}}))

(a/defconst view-direction-out
  (extern [:* {:addrspace :output} [:vector 3 :f32]]
          {:name "view_direction_out" :decoration {:location 4}}))

(a/defconst vertex-color
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "vertex_color" :decoration {:location 0}}))

(a/defconst world-normal
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "world_normal" :decoration {:location 1}}))

(a/defconst world-position
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "world_position" :decoration {:location 2}}))

(a/defconst roughness
  (extern [:* {:addrspace :input} :f32] {:name "roughness" :decoration {:location 3}}))

(a/defconst view-direction
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "view_direction" :decoration {:location 4}}))

(a/defconst out-color
  (extern [:* {:addrspace :output} [:vector 4 :f32]]
          {:name "out_color" :decoration {:location 0}}))

(a/defn- xyz [:vector 3 :f32]
  [[v [:vector 4 :f32]]]
  (a/vector [(a/get v 0) (a/get v 1) (a/get v 2)] :f32))

(a/defn- xy [:vector 2 :f32]
  [[v [:vector 3 :f32]]]
  (a/vector [(a/get v 0) (a/get v 1)] :f32))

(a/defn- rgba [:vector 4 :f32]
  [[v [:vector 3 :f32]]]
  (a/vector [(a/get v 0) (a/get v 1) (a/get v 2) 1.0] :f32))

(a/defn- splat3 [:vector 3 :f32]
  [[v :f32]]
  (splat v))

(a/defn- splat2 [:vector 2 :f32]
  [[v :f32]]
  (splat v))

(a/defn- dot3 :f32
  [[a [:vector 3 :f32]] [b [:vector 3 :f32]]]
  (reduce :Add (* a b)))

(a/defn- mix3 [:vector 3 :f32]
  [[a [:vector 3 :f32]] [b [:vector 3 :f32]] [t :f32]]
  (spv/glsl [:vector 3 :f32] "FMix" a b (splat3 t)))

(a/defn mesh-vertex :void
  {:attrs #{export} :callconv :spirv_vertex}
  []
  (= @clip-position (rgba @in-position))
  (= @vertex-color-out @in-color)
  (= @world-normal-out @in-normal)
  (= @world-position-out @in-world)
  (= @roughness-out @in-roughness)
  (= @view-direction-out @in-view-direction))

(a/defn- rotate [:vector 3 :f32]
  [[v [:vector 3 :f32]]]
  (let [r (xyz @rotation)
        t (* (splat3 2.0) (spv/glsl [:vector 3 :f32] "Cross" r v))]
    (+ v (* (splat3 (a/get @rotation 3)) t) (spv/glsl [:vector 3 :f32] "Cross" r t))))

(a/defn instances-vertex :void
  {:attrs #{export} :callconv :spirv_vertex}
  []
  (= @world-position-out
     (* (+ (rotate (- @position (xyz @pivot-shadow))) (xyz @translation-scale))
        (splat3 (a/get @translation-scale 3))))
  (= @world-normal-out (rotate @normal))
  (= @vertex-color-out (* @color (mix3 (splat3 1.0) (xyz @tint-mode) @tint-weight)))
  (= @roughness-out (if (< (dot3 @color (splat3 1.0)) 0.30) (f32 0.90) (f32 0.34)))
  (when (> (a/get @tint-mode 3) 0.5)
    ;; Independent wheel/chassis poses also drive their planar shadows.
    (when (<= (a/get @world-normal-out 2) 0.5)
      (= @clip-position (a/vector [2.0 2.0 2.0 1.0] :f32))
      (= @view-direction-out (a/vector [0.0 0.0 1.0] :f32))
      (return))
    (let [height (max (f32 0.0) (- (a/get @world-position-out 2) (a/get @pivot-shadow 3)))]
      (+= (a/get @world-position-out 0) (* height 0.34642))
      (+= (a/get @world-position-out 1) (* height 0.46189))
      (= (a/get @world-position-out 2) (+ (a/get @pivot-shadow 3) 0.00006))
      (= @vertex-color-out (a/vector [0.035 0.045 0.060] :f32))
      (= @roughness-out -1.0)))
  (let [position-zoom (:position_zoom @camera)
        angles (:angles @camera)
        fit (:fit @camera)
        p (- @world-position-out (xyz position-zoom))
        rx (- (* (a/get p 0) (a/get angles 0)) (* (a/get p 1) (a/get angles 1)))
        ry (+ (* (a/get p 0) (a/get angles 1)) (* (a/get p 1) (a/get angles 0)))]
    (= @clip-position
       (a/vector
        [(* rx (a/get position-zoom 3) (a/get fit 0))
         (+ 0.10
            (* (- (* (- ry) (a/get angles 3)) (* (a/get p 2) (a/get angles 2)))
               (a/get position-zoom 3)
               (a/get fit 1)))
         (+ 0.5
            (* 0.22 (- (* ry (a/get angles 2)) (* (a/get p 2) (a/get angles 3)))))
         1.0]
        :f32))
    (= @view-direction-out
       (a/vector [(* (- (a/get angles 1)) (a/get angles 2))
                  (* (- (a/get angles 0)) (a/get angles 2)) (a/get angles 3)]
                 :f32))))

(a/defconst PI :f32 3.14159265359)

(a/defn- hash21 :f32
  [[p [:vector 2 :f32]]]
  (let [scaled (* p (a/vector [123.34 456.21] :f32))
        f (- scaled (floor scaled))
        shifted (+ f (splat2 (reduce :Add (* f (+ f (splat2 45.32))))))
        product (* (a/get shifted 0) (a/get shifted 1))]
    (- product (floor product))))

(a/defn- surface-noise :f32
  [[p [:vector 2 :f32]]]
  (let [cell (floor p)
        fract (- p cell)
        f (* fract fract (- (splat2 3.0) (* (splat2 2.0) fract)))]
    (spv/glsl :f32
              "FMix"
              (spv/glsl :f32
                        "FMix"
                        (hash21 cell)
                        (hash21 (+ cell (a/vector [1.0 0.0] :f32)))
                        (a/get f 0))
              (spv/glsl :f32
                        "FMix"
                        (hash21 (+ cell (a/vector [0.0 1.0] :f32)))
                        (hash21 (+ cell (a/vector [1.0 1.0] :f32)))
                        (a/get f 0))
              (a/get f 1))))

(a/defn- tonemap [:vector 3 :f32]
  [[x [:vector 3 :f32]]]
  (spv/glsl [:vector 3 :f32]
            "FClamp"
            (/ (* x (+ (* (splat3 2.51) x) (splat3 0.03)))
               (+ (* x (+ (* (splat3 2.43) x) (splat3 0.59))) (splat3 0.14)))
            (splat3 0.0)
            (splat3 1.0)))

(a/defn fragment :void
  {:attrs #{export} :callconv {:spirv_fragment {}}}
  []
  ;; HUD and projected shadow geometry are deliberately unlit.
  (when (< @roughness 0.0) (= @out-color (rgba @vertex-color)) (return))
  (let [n (spv/glsl [:vector 3 :f32] "Normalize" @world-normal)
        v (spv/glsl [:vector 3 :f32] "Normalize" @view-direction)
        l (spv/glsl [:vector 3 :f32] "Normalize" (a/vector [-0.30 -0.40 0.866] :f32))
        h (spv/glsl [:vector 3 :f32] "Normalize" (+ l v))
        nl (max (dot3 n l) 0.0)
        nv (max (dot3 n v) 0.001)
        nh (max (dot3 n h) 0.0)
        vh (max (dot3 v h) 0.0)
        a (max (* @roughness @roughness) 0.025)
        a2 (* a a)
        den (+ (* nh nh (- a2 1.0)) 1.0)
        distribution (/ a2 (max (* PI den den) 0.0001))
        visibility-k (/ (* (+ @roughness 1.0) (+ @roughness 1.0)) 8.0)
        visibility (* (/ nv (+ (* nv (- 1.0 visibility-k)) visibility-k))
                      (/ nl (max (+ (* nl (- 1.0 visibility-k)) visibility-k) 0.001)))
        fresnel (+ (splat3 0.04)
                   (* (splat3 0.96) (splat3 (spv/glsl :f32 "Pow" (- 1.0 vh) (f32 5.0)))))
        specular (/ (* (splat3 (* distribution visibility)) fresnel)
                    (splat3 (max (* 4.0 nv nl) 0.001)))
        albedo (k/var
                (spv/glsl [:vector 3 :f32] "Pow" (max @vertex-color (splat3 0.0)) (splat3 2.2)))]
    ;; World-metre detail: broad turf/asphalt variation remains readable at
    ;; broadcast distance; fine grain fades out instead of shimmering. The
    ;; current terrain palette identifies turf; voxel paint is unaffected.
    (when (> @roughness 0.85)
      (let [metres (* (xy @world-position) (splat2 1000.0))
            footprint (max (spv/glsl :f32
                                     "Length"
                                     (spv/instruction [:vector 2 :f32] "OpFwidth" metres))
                           0.001)
            coarse (surface-noise (* metres (splat2 0.45)))
            grain (surface-noise (* metres (splat2 18.0)))
            detail (- 1.0 (spv/glsl :f32 "SmoothStep" (f32 0.015) (f32 0.12) footprint))
            turf (and (> (a/get @vertex-color 1) (* (a/get @vertex-color 0) 1.35))
                      (> (a/get @vertex-color 1) (* (a/get @vertex-color 2) 1.35)))]
        (if turf
          (let [turf-patch (surface-noise (* metres (splat2 0.08)))]
            (*=
             albedo
             (mix3 (a/vector [0.72 0.82 0.65] :f32) (a/vector [1.22 1.16 0.90] :f32) turf-patch))
            (*= albedo (splat3 (+ 0.85 (* 0.30 coarse) (* (- grain 0.5) 0.22 detail)))))
          (*= albedo (splat3 (+ 0.94 (* 0.12 coarse) (* (- grain 0.5) 0.22 detail)))))))
    (let [sun (a/vector [3.2 2.95 2.60] :f32)
          ambient (mix3 (a/vector [0.13 0.11 0.08] :f32)
                        (a/vector [0.40 0.50 0.68] :f32)
                        (min (max (+ (* (a/get n 2) 0.5) 0.5) 0.0) 1.0))
          radiance (+ (* albedo ambient)
                      (* (+ (/ (* (- (splat3 1.0) fresnel) albedo) (splat3 PI))
                            specular)
                         sun
                         (splat3 nl)))]
      ;; The Vulkan swapchain is UNORM; explicitly encode display gamma
      ;; here.
      (= @out-color
         (rgba (spv/glsl [:vector 3 :f32]
                         "Pow"
                         (tonemap (* radiance (splat3 1.25)))
                         (splat3 (/ 1.0 2.2))))))))
