(ns learn.example.runtime-divExact-remainder
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u32} numerator 10
        ^{:var :u32} denominator 3]
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [quotient (ak/divExact numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))
