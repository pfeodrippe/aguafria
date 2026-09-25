(ns learn.example.runtime-remainder-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [numerator (k/var 10 :u32)
        denominator (k/var 0 :u32)]
    (k/= :_ [(k/& numerator) (k/& denominator)])
    (let [remainder (k/% numerator denominator)]
      (debug/print "value: {}\n" [remainder]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
