(ns learn.examples.idiomatic-basics.mutable-var
  "Converted from mutable_var.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:var :i32} value 5678]
    (ak/+= value 1)
    (debug/print "{d}" [value])))
