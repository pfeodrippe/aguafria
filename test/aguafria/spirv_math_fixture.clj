(ns aguafria.spirv-math-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.spirv :as spv]
            [aguafria.zig :as a]))

(a/defconst input
  (extern [:* {:addrspace :input} [:vector 3 :f32]]
          {:name "input" :decoration {:location 0}}))

(a/defconst output
  (extern [:* {:addrspace :output} [:vector 4 :f32]]
          {:name "output" :decoration {:location 0}}))

(a/defn fragment :void
  {:attrs #{export} :callconv {:spirv_fragment {}}}
  []
  (let [direction (spv/glsl [:vector 3 :f32] "Normalize" @input)
        intensity (exp (a/get @input 0))]
    (= @output
       (a/vector [(a/get direction 0)
                  (a/get direction 1)
                  (a/get direction 2)
                  intensity]
                 :f32))))
