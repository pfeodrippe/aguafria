(ns learn.example.destructuring-to-existing
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:var :u32} x ak/undefined
        ^{:var :u32} y ak/undefined
        ^{:var :u32} z ak/undefined
        tuple [1 2 3]
        numbers (az/array-init [:array _ :u32] [4 5 6])
        ^{:zig/type [:vector 3 :u32]} lanes [7 8 9]]
    ;; A vector target assigns existing bindings; it does not declare new ones.
    (set! [x y z] tuple)
    (debug/print "tuple: x = {}, y = {}, z = {}\n" [x y z])
    (set! [x y z] numbers)
    (debug/print "array: x = {}, y = {}, z = {}\n" [x y z])
    (set! [x y z] lanes)
    (debug/print "vector: x = {}, y = {}, z = {}\n" [x y z])))
