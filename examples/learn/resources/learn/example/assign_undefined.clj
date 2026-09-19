(ns learn.example.assign-undefined
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:var :i32} value ak/undefined]
    (set! value 1)
    (debug/print "{d}" [value])))
