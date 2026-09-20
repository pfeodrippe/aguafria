(ns learn.example.test-comptime-remainder-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-remainder-divisor
  (let [numerator (ak/i32 10)
        denominator (ak/i32 0)
        remainder (ak/% numerator denominator)]
    (set! _ remainder)))
