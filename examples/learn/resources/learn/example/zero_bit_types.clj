(ns learn.example.zero-bit-types
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{ak/export}} []
  (let [first-value (ak/var (az/block) :void)
        second-value (ak/var (az/block) :void)]
    (ak/= first-value second-value)
    (ak/= second-value first-value)))

(comment
  (entry))
