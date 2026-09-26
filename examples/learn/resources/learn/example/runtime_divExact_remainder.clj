(ns learn.example.runtime-divExact-remainder
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [a (k/var 10 :u32)
        b (k/var 3 :u32)]
    (k/= :_ [(k/& a) (k/& b)])
    (let [c (k/divExact a b)]
      (debug/print "value: {}\n" [c]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
