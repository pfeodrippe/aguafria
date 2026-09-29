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
        array (az/array [4 5 6] :u32)
        vector (az/vector [7 8 9] :u32)]
    (k/= [x y z] tuple)
    (debug/print "tuple: x = {}, y = {}, z = {}\n" [x y z])
    (k/= [x y z] array)
    (debug/print "array: x = {}, y = {}, z = {}\n" [x y z])
    (k/= [x y z] vector)
    (debug/print "vector: x = {}, y = {}, z = {}\n" [x y z])))

(comment
  (main))
