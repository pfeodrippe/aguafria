(ns learn.example.zero-bit-types
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{k/export}} []
  (let [x (k/var (az/block) :void)
        y (k/var (az/block) :void)]
    (k/= x y)
    (k/= y x)))

(comment
  (entry))
