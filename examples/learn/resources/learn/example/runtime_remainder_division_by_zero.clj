(ns learn.example.runtime-remainder-division-by-zero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [numerator (ak/var 10 :u32)
        denominator (ak/var 0 :u32)]
    (ak/= :_ [(ak/& numerator) (ak/& denominator)])
    (let [remainder (ak/% numerator denominator)]
      (debug/print "value: {}\n" [remainder]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
