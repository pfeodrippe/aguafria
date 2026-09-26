(ns learn.example.test-comptime-remainder-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-remainder-divisor
  (let [a (k/i32 10)
        b (k/i32 0)
        c (k/% a b)]
    (k/= :_ c)))
