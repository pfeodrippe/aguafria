(ns learn.example.destructuring-to-existing
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [x (ak/var ak/undefined :u32)
        y (ak/var ak/undefined :u32)
        z (ak/var ak/undefined :u32)
        tuple [1 2 3]
        numbers (az/array-init [4 5 6] [:array :_ :u32])
        lanes (ak/as [7 8 9] [:vector 3 :u32])]
    ;; A vector target assigns existing bindings; it does not declare new ones.
    (ak/= [x y z] tuple)
    (debug/print "tuple: x = {}, y = {}, z = {}\n" [x y z])
    (ak/= [x y z] numbers)
    (debug/print "array: x = {}, y = {}, z = {}\n" [x y z])
    (ak/= [x y z] lanes)
    (debug/print "vector: x = {}, y = {}, z = {}\n" [x y z])))

(comment
  (main))
