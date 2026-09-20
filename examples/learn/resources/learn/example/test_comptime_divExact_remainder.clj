(ns learn.example.test-comptime-divExact-remainder
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Exact division promises a zero remainder; ten divided by three violates it.
(az/defcomptime reject-inexact-division
  (let [numerator (ak/u32 10)
        denominator (ak/u32 3)
        quotient (ak/divExact numerator denominator)]
    (set! _ quotient)))
