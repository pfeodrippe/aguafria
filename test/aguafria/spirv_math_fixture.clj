(ns aguafria.spirv-math-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.spirv :as spv]
            [aguafria.zig :as a]))

(a/defconst input
  (k/extern [:* {:addrspace :.input} [:vector 3 :f32]]
            {:name "input" :decoration {:location 0}}))

(a/defconst output
  (k/extern [:* {:addrspace :.output} [:vector 4 :f32]]
            {:name "output" :decoration {:location 0}}))

(a/defn fragment :void
  {:attrs #{k/export} :callconv {:spirv_fragment {}}}
  []
  (let [direction (spv/glsl [:vector 3 :f32] "Normalize" @input)
        intensity (k/exp (a/get @input 0))]
    (k/= @output
         (a/vector [(a/get direction 0)
                     (a/get direction 1)
                     (a/get direction 2)
                     intensity]
                    :f32))))
