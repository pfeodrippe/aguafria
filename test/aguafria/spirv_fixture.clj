(ns aguafria.spirv-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.spirv-fixture-options :as options]
            [aguafria.zig :as a]))

(a/defconst Vertices (SpirvType {:runtime_array (a/type [:vector 4 :f32])}))

(a/defstruct Buffer {:layout :extern} [[:data Vertices]])

(a/defconst vertices
  (extern [:* {:addrspace :storage_buffer} Buffer]
          {:name "vertices" :decoration {:descriptor {:set 0 :binding 0}}}))

(a/defconst position (extern [:* {:addrspace :output} [:vector 4 :f32]] {:name "position"}))

(a/defn main :void
  {:attrs #{export} :callconv :spirv_vertex}
  []
  (= @position (a/get (:data @vertices) 0)))

(a/defconst intensity :f32 (a/clj! options/*intensity*))

(a/defconst color
  (extern [:* {:addrspace :output} [:vector 4 :f32]]
          {:name "color" :decoration {:location 0}}))

(a/defn fragment :void
  {:attrs #{export} :callconv {:spirv_fragment {}}}
  []
  (= @color (a/vector [intensity 0.0 0.0 1.0] :f32)))
