(ns learn.example.test-comptime-divExact-remainder
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-inexact-division
  (let [a (k/u32 10)
        b (k/u32 3)
        c (k/divExact a b)]
    (k/= :_ c)))
