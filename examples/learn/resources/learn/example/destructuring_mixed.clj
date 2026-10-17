(ns learn.example.destructuring-mixed
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [tuple [1 2 3]
        [x y z] tuple
        x (k/var x :u32)
        y (k/var y :u32)]
    (debug/print "x = {}, y = {}, z = {}\n" [x y z])
    ;; y is mutable
    (= y 100)
    ;; You can use _ to throw away unwanted values.
    (= [:_ x :_] tuple)
    (debug/print "x = {}\n" [x])))

(comment
  (main))
