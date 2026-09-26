(ns learn.example.runtime-division-by-zero
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [a (k/var 1 :u32)
        b (k/var 0 :u32)]
    (k/= :_ [(k/& a) (k/& b)])
    (let [c (k// a b)]
      (debug/print "value: {}\n" [c]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
