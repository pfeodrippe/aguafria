(ns learn.example.destructuring-to-existing
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^:var x (ak/u32 ak/undefined)
        ^:var y (ak/u32 ak/undefined)
        ^:var z (ak/u32 ak/undefined)
        tuple [1 2 3]
        numbers (az/array-init [:array _ :u32] [4 5 6])
        lanes (ak/as [7 8 9] [:vector 3 :u32])]
    ;; A vector target assigns existing bindings; it does not declare new ones.
    (set! [x y z] tuple)
    (debug/print "tuple: x = {}, y = {}, z = {}\n" [x y z])
    (set! [x y z] numbers)
    (debug/print "array: x = {}, y = {}, z = {}\n" [x y z])
    (set! [x y z] lanes)
    (debug/print "vector: x = {}, y = {}, z = {}\n" [x y z])))

(comment
  (main))
