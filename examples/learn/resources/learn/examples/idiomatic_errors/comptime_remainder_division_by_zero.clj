(ns learn.examples.idiomatic-errors.comptime-remainder-division-by-zero
  "Converted from test_comptime_remainder_division_by_zero.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-zero-remainder-divisor
  (let [^{:zig/type :i32} numerator 10
        ^{:zig/type :i32} denominator 0
        remainder (ak/% numerator denominator)]
    (set! _ remainder)))
