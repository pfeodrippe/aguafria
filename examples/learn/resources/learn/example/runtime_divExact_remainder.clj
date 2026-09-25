(ns learn.example.runtime-divExact-remainder
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [numerator (k/var 10 :u32)
        denominator (k/var 3 :u32)]
    (k/= :_ [(k/& numerator) (k/& denominator)])
    (let [quotient (k/divExact numerator denominator)]
      (debug/print "value: {}\n" [quotient]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
