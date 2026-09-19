(ns learn.example.runtime-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u32} numerator 1
        ^{:var :u32} denominator 0]
    ;; Taking addresses keeps the operands runtime-known.
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [quotient (/ numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))
