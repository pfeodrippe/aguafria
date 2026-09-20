(ns learn.example.zero-bit-types
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{:export}} []
  (let [^:var first-value (ak/as (az/block) :void)
        ^:var second-value (ak/as (az/block) :void)]
    (set! first-value second-value)
    (set! second-value first-value)))

(comment
  (entry))
