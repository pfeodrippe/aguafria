(ns learn.example.runtime-shlExact-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [x (k/var 2r01010101 :u8)] ; runtime-known
    (= :_ (k/& x))
    (let [y (shlExact x 2)]
      (debug/print "value: {}\n" [y]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
