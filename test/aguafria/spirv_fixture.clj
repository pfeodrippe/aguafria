(ns aguafria.spirv-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.spirv-fixture-options :as options]
            [aguafria.zig :as a]))

(a/defconst Vertices (k/SpirvType {:runtime_array (a/type [:vector 4 :f32])}))

(a/defstruct Buffer {:layout :extern} [[:data Vertices]])

(a/defconst vertices
  (k/extern [:* {:addrspace :.storage_buffer} Buffer]
            {:name "vertices" :decoration {:descriptor {:set 0 :binding 0}}}))

(a/defconst position (k/extern [:* {:addrspace :.output} [:vector 4 :f32]] {:name "position"}))

(a/defn main :void
  {:attrs #{k/export} :callconv :.spirv_vertex}
  []
  (k/= @position (a/get (:data @vertices) 0)))

(a/defconst intensity :f32 (a/clj! options/*intensity*))

(a/defconst color
  (k/extern [:* {:addrspace :.output} [:vector 4 :f32]]
            {:name "color" :decoration {:location 0}}))

(a/defn fragment :void
  {:attrs #{k/export} :callconv {:spirv_fragment {}}}
  []
  (k/= @color (a/vector [intensity 0.0 0.0 1.0] :f32)))
