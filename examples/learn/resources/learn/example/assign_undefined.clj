(ns learn.example.assign-undefined
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^:var value (ak/i32 ak/undefined)]
    (set! value 1)
    (debug/print "{d}" [value])))

(comment
  (main))
