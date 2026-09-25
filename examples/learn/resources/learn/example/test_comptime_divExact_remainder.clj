(ns learn.example.test-comptime-divExact-remainder
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; Exact division promises a zero remainder; ten divided by three violates it.
(az/defcomptime reject-inexact-division
  (let [numerator (k/u32 10)
        denominator (k/u32 3)
        quotient (k/divExact numerator denominator)]
    (k/= :_ quotient)))
