(ns racing-game.shaders
  "Mesh, instanced mesh and fragment stages for the native Vulkan renderer."
  (:require [aguafria.keyword :as k]
            [aguafria.spirv :as spv]
            [aguafria.zig :as a]))

(a/defconst clip-position
  (k/extern [:* {:addrspace :.output} [:vector 4 :f32]] {:name "position"}))

(a/defconst in-position
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "in_position" :decoration {:location 0}}))

(a/defconst in-color
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "in_color" :decoration {:location 1}}))

(a/defconst in-normal
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "in_normal" :decoration {:location 2}}))

(a/defconst in-world
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "in_world" :decoration {:location 3}}))

(a/defconst in-roughness
  (k/extern [:* {:addrspace :.input} :f32] {:name "in_roughness" :decoration {:location 4}}))

(a/defconst in-view-direction
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "in_view_direction" :decoration {:location 5}}))

;; Authored mesh uploaded once, shared by all cars. No CPU-expanded vertices.
(a/defconst position
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "instance_position" :decoration {:location 0}}))

(a/defconst normal
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]] {:name "normal" :decoration {:location 1}}))

(a/defconst color
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]] {:name "color" :decoration {:location 2}}))

(a/defconst tint-weight
  (k/extern [:* {:addrspace :.input} :f32] {:name "tint_weight" :decoration {:location 3}}))

(a/defconst rotation
  (k/extern [:* {:addrspace :.input} [:vector 4 :f32]]
            {:name "rotation" :decoration {:location 4}}))

(a/defconst translation-scale
  (k/extern [:* {:addrspace :.input} [:vector 4 :f32]]
            {:name "translation_scale" :decoration {:location 5}}))

(a/defconst pivot-shadow
  (k/extern [:* {:addrspace :.input} [:vector 4 :f32]]
            {:name "pivot_shadow" :decoration {:location 6}}))

(a/defconst tint-mode
  (k/extern [:* {:addrspace :.input} [:vector 4 :f32]]
            {:name "tint_mode" :decoration {:location 7}}))

(a/defstruct Camera
  {:layout :extern}
  [[:position_zoom [:vector 4 :f32]]
   ;; cos(yaw), sin(yaw), cos(pitch), sin(pitch)
   [:angles [:vector 4 :f32]]
   [:fit [:vector 4 :f32]]])

(a/defconst camera
  (k/extern [:* {:addrspace :.push_constant :const? true} Camera] {:name "camera"}))

(a/defconst vertex-color-out
  (k/extern [:* {:addrspace :.output} [:vector 3 :f32]]
            {:name "vertex_color_out" :decoration {:location 0}}))

(a/defconst world-normal-out
  (k/extern [:* {:addrspace :.output} [:vector 3 :f32]]
            {:name "world_normal_out" :decoration {:location 1}}))

(a/defconst world-position-out
  (k/extern [:* {:addrspace :.output} [:vector 3 :f32]]
            {:name "world_position_out" :decoration {:location 2}}))

(a/defconst roughness-out
  (k/extern [:* {:addrspace :.output} :f32] {:name "roughness_out" :decoration {:location 3}}))

(a/defconst view-direction-out
  (k/extern [:* {:addrspace :.output} [:vector 3 :f32]]
            {:name "view_direction_out" :decoration {:location 4}}))

(a/defconst vertex-color
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "vertex_color" :decoration {:location 0}}))

(a/defconst world-normal
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "world_normal" :decoration {:location 1}}))

(a/defconst world-position
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "world_position" :decoration {:location 2}}))

(a/defconst roughness
  (k/extern [:* {:addrspace :.input} :f32] {:name "roughness" :decoration {:location 3}}))

(a/defconst view-direction
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "view_direction" :decoration {:location 4}}))

(a/defconst out-color
  (k/extern [:* {:addrspace :.output} [:vector 4 :f32]]
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
  (k/splat v))

(a/defn- splat2 [:vector 2 :f32]
  [[v :f32]]
  (k/splat v))

(a/defn- dot3 :f32
  [[a [:vector 3 :f32]] [b [:vector 3 :f32]]]
  (k/reduce :.Add (k/* a b)))

(a/defn- mix3 [:vector 3 :f32]
  [[a [:vector 3 :f32]] [b [:vector 3 :f32]] [t :f32]]
  (spv/glsl [:vector 3 :f32] "FMix" a b (splat3 t)))

(a/defn mesh-vertex :void
  {:attrs #{k/export} :callconv :.spirv_vertex}
  []
  (k/= @clip-position (rgba @in-position))
  (k/= @vertex-color-out @in-color)
  (k/= @world-normal-out @in-normal)
  (k/= @world-position-out @in-world)
  (k/= @roughness-out @in-roughness)
  (k/= @view-direction-out @in-view-direction))

(a/defn- rotate [:vector 3 :f32]
  [[v [:vector 3 :f32]]]
  (let [r (xyz @rotation)
        t (k/* (splat3 2.0) (spv/glsl [:vector 3 :f32] "Cross" r v))]
    (k/+ v (k/* (splat3 (a/get @rotation 3)) t) (spv/glsl [:vector 3 :f32] "Cross" r t))))

(a/defn instances-vertex :void
  {:attrs #{k/export} :callconv :.spirv_vertex}
  []
  (k/= @world-position-out
       (k/* (k/+ (rotate (k/- @position (xyz @pivot-shadow))) (xyz @translation-scale))
            (splat3 (a/get @translation-scale 3))))
  (k/= @world-normal-out (rotate @normal))
  (k/= @vertex-color-out (k/* @color (mix3 (splat3 1.0) (xyz @tint-mode) @tint-weight)))
  (k/= @roughness-out (if (k/< (dot3 @color (splat3 1.0)) 0.30) (k/f32 0.90) (k/f32 0.34)))
  (when (k/> (a/get @tint-mode 3) 0.5)
    ;; Independent wheel/chassis poses also drive their planar shadows.
    (when (k/<= (a/get @world-normal-out 2) 0.5)
      (k/= @clip-position (a/vector [2.0 2.0 2.0 1.0] :f32))
      (k/= @view-direction-out (a/vector [0.0 0.0 1.0] :f32))
      (k/return))
    (let [height (k/max (k/f32 0.0) (k/- (a/get @world-position-out 2) (a/get @pivot-shadow 3)))]
      (k/+= (a/get @world-position-out 0) (k/* height 0.34642))
      (k/+= (a/get @world-position-out 1) (k/* height 0.46189))
      (k/= (a/get @world-position-out 2) (k/+ (a/get @pivot-shadow 3) 0.00006))
      (k/= @vertex-color-out (a/vector [0.035 0.045 0.060] :f32))
      (k/= @roughness-out -1.0)))
  (let [position-zoom (:position_zoom @camera)
        angles (:angles @camera)
        fit (:fit @camera)
        p (k/- @world-position-out (xyz position-zoom))
        rx (k/- (k/* (a/get p 0) (a/get angles 0)) (k/* (a/get p 1) (a/get angles 1)))
        ry (k/+ (k/* (a/get p 0) (a/get angles 1)) (k/* (a/get p 1) (a/get angles 0)))]
    (k/= @clip-position
         (a/vector
           [(k/* rx (a/get position-zoom 3) (a/get fit 0))
            (k/+ 0.10
                 (k/* (k/- (k/* (k/- ry) (a/get angles 3)) (k/* (a/get p 2) (a/get angles 2)))
                      (a/get position-zoom 3)
                      (a/get fit 1)))
            (k/+ 0.5
                 (k/* 0.22 (k/- (k/* ry (a/get angles 2)) (k/* (a/get p 2) (a/get angles 3)))))
            1.0]
           :f32))
    (k/= @view-direction-out
         (a/vector [(k/* (k/- (a/get angles 1)) (a/get angles 2))
                     (k/* (k/- (a/get angles 0)) (a/get angles 2)) (a/get angles 3)]
                    :f32))))

(a/defconst PI :f32 3.14159265359)

(a/defn- hash21 :f32
  [[p [:vector 2 :f32]]]
  (let [scaled (k/* p (a/vector [123.34 456.21] :f32))
        f (k/- scaled (k/floor scaled))
        shifted (k/+ f (splat2 (k/reduce :.Add (k/* f (k/+ f (splat2 45.32))))))
        product (k/* (a/get shifted 0) (a/get shifted 1))]
    (k/- product (k/floor product))))

(a/defn- surface-noise :f32
  [[p [:vector 2 :f32]]]
  (let [cell (k/floor p)
        fract (k/- p cell)
        f (k/* fract fract (k/- (splat2 3.0) (k/* (splat2 2.0) fract)))]
    (spv/glsl :f32
              "FMix"
              (spv/glsl :f32
                        "FMix"
                        (hash21 cell)
                        (hash21 (k/+ cell (a/vector [1.0 0.0] :f32)))
                        (a/get f 0))
              (spv/glsl :f32
                        "FMix"
                        (hash21 (k/+ cell (a/vector [0.0 1.0] :f32)))
                        (hash21 (k/+ cell (a/vector [1.0 1.0] :f32)))
                        (a/get f 0))
              (a/get f 1))))

(a/defn- tonemap [:vector 3 :f32]
  [[x [:vector 3 :f32]]]
  (spv/glsl [:vector 3 :f32]
            "FClamp"
            (k// (k/* x (k/+ (k/* (splat3 2.51) x) (splat3 0.03)))
                 (k/+ (k/* x (k/+ (k/* (splat3 2.43) x) (splat3 0.59))) (splat3 0.14)))
            (splat3 0.0)
            (splat3 1.0)))

(a/defn fragment :void
  {:attrs #{k/export} :callconv {:spirv_fragment {}}}
  []
  ;; HUD and projected shadow geometry are deliberately unlit.
  (when (k/< @roughness 0.0) (k/= @out-color (rgba @vertex-color)) (k/return))
  (let [n (spv/glsl [:vector 3 :f32] "Normalize" @world-normal)
        v (spv/glsl [:vector 3 :f32] "Normalize" @view-direction)
        l (spv/glsl [:vector 3 :f32] "Normalize" (a/vector [-0.30 -0.40 0.866] :f32))
        h (spv/glsl [:vector 3 :f32] "Normalize" (k/+ l v))
        nl (k/max (dot3 n l) 0.0)
        nv (k/max (dot3 n v) 0.001)
        nh (k/max (dot3 n h) 0.0)
        vh (k/max (dot3 v h) 0.0)
        a (k/max (k/* @roughness @roughness) 0.025)
        a2 (k/* a a)
        den (k/+ (k/* nh nh (k/- a2 1.0)) 1.0)
        distribution (k// a2 (k/max (k/* PI den den) 0.0001))
        visibility-k (k// (k/* (k/+ @roughness 1.0) (k/+ @roughness 1.0)) 8.0)
        visibility (k/* (k// nv (k/+ (k/* nv (k/- 1.0 visibility-k)) visibility-k))
                        (k// nl (k/max (k/+ (k/* nl (k/- 1.0 visibility-k)) visibility-k) 0.001)))
        fresnel (k/+ (splat3 0.04)
                     (k/* (splat3 0.96) (splat3 (spv/glsl :f32 "Pow" (k/- 1.0 vh) (k/f32 5.0)))))
        specular (k// (k/* (splat3 (k/* distribution visibility)) fresnel)
                      (splat3 (k/max (k/* 4.0 nv nl) 0.001)))
        albedo (k/var
                 (spv/glsl [:vector 3 :f32] "Pow" (k/max @vertex-color (splat3 0.0)) (splat3 2.2)))]
    ;; World-metre detail: broad turf/asphalt variation remains readable at
    ;; broadcast distance; fine grain fades out instead of shimmering. The
    ;; current terrain palette identifies turf; voxel paint is unaffected.
    (when (k/> @roughness 0.85)
      (let [metres (k/* (xy @world-position) (splat2 1000.0))
            footprint (k/max (spv/glsl :f32
                                       "Length"
                                       (spv/instruction [:vector 2 :f32] "OpFwidth" metres))
                             0.001)
            coarse (surface-noise (k/* metres (splat2 0.45)))
            grain (surface-noise (k/* metres (splat2 18.0)))
            detail (k/- 1.0 (spv/glsl :f32 "SmoothStep" (k/f32 0.015) (k/f32 0.12) footprint))
            turf (and (k/> (a/get @vertex-color 1) (k/* (a/get @vertex-color 0) 1.35))
                      (k/> (a/get @vertex-color 1) (k/* (a/get @vertex-color 2) 1.35)))]
        (if turf
          (let [turf-patch (surface-noise (k/* metres (splat2 0.08)))]
            (k/*=
              albedo
              (mix3 (a/vector [0.72 0.82 0.65] :f32) (a/vector [1.22 1.16 0.90] :f32) turf-patch))
            (k/*= albedo (splat3 (k/+ 0.85 (k/* 0.30 coarse) (k/* (k/- grain 0.5) 0.22 detail)))))
          (k/*= albedo (splat3 (k/+ 0.94 (k/* 0.12 coarse) (k/* (k/- grain 0.5) 0.22 detail)))))))
    (let [sun (a/vector [3.2 2.95 2.60] :f32)
          ambient (mix3 (a/vector [0.13 0.11 0.08] :f32)
                        (a/vector [0.40 0.50 0.68] :f32)
                        (k/min (k/max (k/+ (k/* (a/get n 2) 0.5) 0.5) 0.0) 1.0))
          radiance (k/+ (k/* albedo ambient)
                        (k/* (k/+ (k// (k/* (k/- (splat3 1.0) fresnel) albedo) (splat3 PI))
                                  specular)
                             sun
                             (splat3 nl)))]
      ;; The Vulkan swapchain is UNORM; explicitly encode display gamma
      ;; here.
      (k/= @out-color
           (rgba (spv/glsl [:vector 3 :f32]
                           "Pow"
                           (tonemap (k/* radiance (splat3 1.25)))
                           (splat3 (k// 1.0 2.2))))))))
