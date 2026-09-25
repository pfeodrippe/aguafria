(ns learn.example.zero-bit-types
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{k/export}} []
  (let [first-value (k/var (az/block) :void)
        second-value (k/var (az/block) :void)]
    (k/= first-value second-value)
    (k/= second-value first-value)))

(comment
  (entry))
