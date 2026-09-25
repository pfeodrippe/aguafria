(ns learn.example.test-comptime-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-divisor
  (let [numerator (k/i32 1)
        denominator (k/i32 0)
        quotient (k// numerator denominator)]
    (k/= :_ quotient)))
