(ns learn.example.runtime-shlExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [x (k/var 2r01010101 :u8)] ; runtime-known
    (k/= :_ (k/& x))
    (let [y (k/shlExact x 2)]
      (debug/print "value: {}\n" [y]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
