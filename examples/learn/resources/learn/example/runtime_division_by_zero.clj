(ns learn.example.runtime-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var numerator (ak/u32 1)
        ^:var denominator (ak/u32 0)]
    ;; Taking addresses keeps the operands runtime-known.
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [quotient (/ numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
