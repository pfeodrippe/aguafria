(ns learn.example.assign-undefined
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [value (ak/var ak/undefined :i32)]
    (ak/= value 1)
    (debug/print "{d}" [value])))

(comment
  (main))
