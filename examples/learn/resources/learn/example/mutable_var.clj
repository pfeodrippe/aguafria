(ns learn.example.mutable-var
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^:var value (ak/i32 5678)]
    (ak/+= value 1)
    (debug/print "{d}" [value])))

(comment
  (main))
