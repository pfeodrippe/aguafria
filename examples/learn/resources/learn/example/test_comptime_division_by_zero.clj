(ns learn.example.test-comptime-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-divisor
  (let [a (k/i32 1)
        b (k/i32 0)
        c (k// a b)]
    (k/= :_ c)))
