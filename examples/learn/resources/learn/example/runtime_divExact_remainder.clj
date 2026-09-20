(ns learn.example.runtime-divExact-remainder
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var numerator (ak/u32 10)
        ^:var denominator (ak/u32 3)]
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [quotient (ak/divExact numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
