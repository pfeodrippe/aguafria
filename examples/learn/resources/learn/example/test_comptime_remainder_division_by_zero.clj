(ns learn.example.test-comptime-remainder-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-remainder-divisor
  (let [numerator (k/i32 10)
        denominator (k/i32 0)
        remainder (k/% numerator denominator)]
    (k/= :_ remainder)))
