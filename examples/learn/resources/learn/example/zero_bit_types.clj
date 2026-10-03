(ns learn.example.zero-bit-types
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn entry :void {:attrs #{k/export}} []
  (let [x (k/var (a/block) :void)
        y (k/var (a/block) :void)]
    (k/= x y)
    (k/= y x)))

(comment
  (entry))
