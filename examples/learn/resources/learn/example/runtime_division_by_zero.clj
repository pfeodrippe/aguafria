(ns learn.example.runtime-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [numerator (ak/var 1 :u32)
        denominator (ak/var 0 :u32)]
    ;; Taking addresses keeps the operands runtime-known.
    (ak/= :_ [(ak/& numerator) (ak/& denominator)])
    (let [quotient (/ numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
