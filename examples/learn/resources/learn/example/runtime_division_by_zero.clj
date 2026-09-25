(ns learn.example.runtime-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [numerator (k/var 1 :u32)
        denominator (k/var 0 :u32)]
    ;; Taking addresses keeps the operands runtime-known.
    (k/= :_ [(k/& numerator) (k/& denominator)])
    (let [quotient (k// numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
