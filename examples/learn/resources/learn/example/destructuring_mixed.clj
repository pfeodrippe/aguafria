(ns learn.example.destructuring-mixed
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:var :u32} x ak/undefined
        tuple [1 2 3]]
    ;; This Zig-specific form mixes assignment with two new declarations in
    ;; one operation. Use let for ordinary binding-only destructuring.
    (az/destructure {}
      [{:kind :target :target x}
       {:kind :var :name :y :type :u32}
       {:kind :const :name :z}]
      tuple)
    (debug/print "x = {}, y = {}, z = {}\n" [x y z])
    (set! y 100)
    (set! [_ x _] tuple)
    (debug/print "x = {}" [x])))

(comment
  (main))
