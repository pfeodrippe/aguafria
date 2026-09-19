(ns learn.examples.idiomatic-errors.runtime-remainder-division-by-zero
  "Converted from runtime_remainder_division_by_zero.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u32} numerator 10
        ^{:var :u32} denominator 0]
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [remainder (ak/% numerator denominator)]
      (debug/print "value: {}\n" [remainder]))))
