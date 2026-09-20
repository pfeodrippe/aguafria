(ns learn.example.runtime-remainder-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var numerator (ak/u32 10)
        ^:var denominator (ak/u32 0)]
    (set! _ [(ak/& numerator) (ak/& denominator)])
    (let [remainder (ak/% numerator denominator)]
      (debug/print "value: {}\n" [remainder]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
