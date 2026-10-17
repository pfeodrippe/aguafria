(ns la-professeure.shaders
  "Game and Studio GPU stages. Compiled together into one SPIR-V module."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.spirv :as spv]
            [aguafria.zig :as a]))

(a/defstruct Vertex
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   [:r :f32]
   [:g :f32]
   [:b :f32]
   [:nx :f32]
   [:ny :f32]
   [:nz :f32]
   [:wx :f32]
   [:wy :f32]
   [:wz :f32]
   [:roughness :f32]
   [:vx :f32]
   [:vy :f32]
   [:vz :f32]])

(a/defconst Vertices (SpirvType {:runtime_array Vertex}))

(a/defconst Pixels (SpirvType {:runtime_array :u32}))

(a/defstruct VertexBuffer {:layout :extern} [[:data Vertices]])

(a/defstruct PixelBuffer {:layout :extern} [[:data Pixels]])

(a/defstruct Root
  {:layout :extern}
  [[:light [:vector 2 :f32]]
   [:lighting :f32]
   [:reserved :f32]])

(a/defconst vertices
  (extern [:* {:addrspace :storage_buffer :const? true} VertexBuffer]
          {:name "vertices" :decoration {:descriptor {:set 0 :binding 0}}}))

(a/defconst pixels
  (extern [:* {:addrspace :storage_buffer :const? true} PixelBuffer]
          {:name "pixels" :decoration {:descriptor {:set 0 :binding 1}}}))

(a/defconst root (extern [:* {:addrspace :push_constant :const? true} Root] {:name "root"}))

(a/defconst vertex-index (extern [:* {:addrspace :input} :u32] {:name "vertex_index"}))

(a/defconst clip-position
  (extern [:* {:addrspace :output} [:vector 4 :f32]] {:name "position"}))

(a/defconst color-out
  (extern [:* {:addrspace :output} [:vector 3 :f32]]
          {:name "color_out" :decoration {:location 0}}))

(a/defconst uv-out
  (extern [:* {:addrspace :output} [:vector 2 :f32]] {:name "uv_out" :decoration {:location 1}}))

(a/defconst position-out
  (extern [:* {:addrspace :output} [:vector 2 :f32]]
          {:name "position_out" :decoration {:location 2}}))

(a/defconst textured-out
  (extern [:* {:addrspace :output} :f32] {:name "textured_out" :decoration {:flat 3}}))

(a/defconst lit-out
  (extern [:* {:addrspace :output} :f32] {:name "lit_out" :decoration {:flat 4}}))

(a/defconst color-in
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "color_in" :decoration {:location 0}}))

(a/defconst uv-in
  (extern [:* {:addrspace :input} [:vector 2 :f32]] {:name "uv_in" :decoration {:location 1}}))

(a/defconst position-in
  (extern [:* {:addrspace :input} [:vector 2 :f32]]
          {:name "position_in" :decoration {:location 2}}))

(a/defconst textured-in
  (extern [:* {:addrspace :input} :f32] {:name "textured_in" :decoration {:flat 3}}))

(a/defconst lit-in
  (extern [:* {:addrspace :input} :f32] {:name "lit_in" :decoration {:flat 4}}))

(a/defconst result
  (extern [:* {:addrspace :output} [:vector 4 :f32]] {:name "result" :decoration {:location 0}}))

(a/defn vertex :void
  {:attrs #{export} :callconv :spirv_vertex}
  []
  (let [v (a/get (:data @vertices) @vertex-index)]
    (= @clip-position (a/vector [(:x v) (:y v) 0.0 1.0] :f32))
    (= @color-out (a/vector [(:r v) (:g v) (:b v)] :f32))
    (= @uv-out (a/vector [(:wx v) (:wy v)] :f32))
    (= @position-out
       (+ (* (a/vector [(:x v) (:y v)] :f32) (a/vector [0.5 0.5] :f32))
          (a/vector [0.5 0.5] :f32)))
    (= @textured-out (:roughness v))
    (= @lit-out (:nz v))))

(a/defn- pixel [:vector 4 :f32]
  [[p [:vector 2 :i32]]]
  (let [clamped (min (max p (a/vector [0 0] :i32)) (a/vector [2047 1535] :i32))
        packed (a/get (:data @pixels)
                      (+ (* (as (a/get clamped 1) :u32) 2048)
                         (as (a/get clamped 0) :u32)))]
    (spv/glsl [:vector 4 :f32] "UnpackUnorm4x8" packed)))

(a/defn fragment :void
  {:attrs #{export} :callconv {:spirv_fragment {}}}
  []
  (let [texel (k/var (a/vector [1.0 1.0 1.0 1.0] :f32))]
    (when (> @textured-in 0.5)
      (let [p (- @uv-in (a/vector [0.5 0.5] :f32))
            base (as (intFromFloat (floor p)) [:vector 2 :i32])
            f (- p (floor p))
            fx (as (splat (a/get f 0)) [:vector 4 :f32])
            fy (as (splat (a/get f 1)) [:vector 4 :f32])]
        (= texel
           (spv/glsl [:vector 4 :f32]
                     "FMix"
                     (spv/glsl [:vector 4 :f32]
                               "FMix"
                               (pixel base)
                               (pixel (+ base (a/vector [1 0] :i32)))
                               fx)
                     (spv/glsl [:vector 4 :f32]
                               "FMix"
                               (pixel (+ base (a/vector [0 1] :i32)))
                               (pixel (+ base (a/vector [1 1] :i32)))
                               fx)
                     fy))))
    (when (< (a/get texel 3) 0.005) (spv/discard))
    (let [d (* (- @position-in (:light @root)) (a/vector [(/ 1100.0 760.0) 1.0] :f32))
          illumination (spv/glsl :f32
                                 "FMix"
                                 (f32 1.0)
                                 (+ 0.80
                                    (* 0.35 (exp (* -12.0 (reduce :Add (* d d))))))
                                 (* @lit-in (:lighting @root)))
          rgb (* @color-in
                 (a/vector [(a/get texel 0) (a/get texel 1) (a/get texel 2)] :f32)
                 (as (splat illumination) [:vector 3 :f32]))]
      (= @result
         (a/vector [(a/get rgb 0) (a/get rgb 1) (a/get rgb 2) (a/get texel 3)] :f32)))))
