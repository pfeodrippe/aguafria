(ns learn.examples.idiomatic-errors.comptime-divExact-remainder
  "Converted from test_comptime_divExact_remainder.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Exact division promises a zero remainder; ten divided by three violates it.
(az/defcomptime reject-inexact-division
  (let [^{:zig/type :u32} numerator 10
        ^{:zig/type :u32} denominator 3
        quotient (ak/divExact numerator denominator)]
    (set! _ quotient)))
