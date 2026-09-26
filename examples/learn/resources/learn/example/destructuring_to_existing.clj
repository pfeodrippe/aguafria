(ns learn.example.destructuring-to-existing
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [x (k/var k/undefined :u32)
        y (k/var k/undefined :u32)
        z (k/var k/undefined :u32)
        tuple [1 2 3]
        numbers (az/array [4 5 6] :u32)
        lanes (k/as [7 8 9] [:vector 3 :u32])]
    ;; A vector target assigns existing bindings; it does not declare new ones.
    (k/= [x y z] tuple)
    (debug/print "tuple: x = {}, y = {}, z = {}\n" [x y z])
    (k/= [x y z] numbers)
    (debug/print "array: x = {}, y = {}, z = {}\n" [x y z])
    (k/= [x y z] lanes)
    (debug/print "vector: x = {}, y = {}, z = {}\n" [x y z])))

(comment
  (main))
