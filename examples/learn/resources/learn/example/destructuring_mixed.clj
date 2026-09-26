(ns learn.example.destructuring-mixed
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [tuple [1 2 3]
        [x y z] tuple
        x (k/var x :u32)
        y (k/var y :u32)]
    (debug/print "x = {}, y = {}, z = {}\n" [x y z])
    ;; y is mutable
    (k/= y 100)
    ;; You can use _ to throw away unwanted values.
    (k/= [:_ x :_] tuple)
    (debug/print "x = {}" [x])))

(comment
  (main))
