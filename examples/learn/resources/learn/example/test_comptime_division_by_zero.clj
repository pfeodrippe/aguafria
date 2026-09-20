(ns learn.example.test-comptime-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-divisor
  (let [numerator (ak/i32 1)
        denominator (ak/i32 0)
        quotient (/ numerator denominator)]
    (set! _ quotient)))
