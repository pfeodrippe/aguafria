(ns learn.examples.idiomatic-errors.comptime-division-by-zero
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-zero-divisor
  (let [^{:zig/type :i32} numerator 1
        ^{:zig/type :i32} denominator 0
        quotient (/ numerator denominator)]
    (set! _ quotient)))
