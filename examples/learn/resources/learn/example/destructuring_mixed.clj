(ns learn.example.destructuring-mixed
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [tuple [1 2 3]
        [x y z] tuple
        x (ak/var x :u32)
        y (ak/var y :u32)]
    (debug/print "x = {}, y = {}, z = {}\n" [x y z])
    (ak/= y 100)
    (ak/= [:_ x :_] tuple)
    (debug/print "x = {}" [x])))

(comment
  (main))
