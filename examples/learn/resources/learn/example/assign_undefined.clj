(ns learn.example.assign-undefined
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [value (k/var k/undefined :i32)]
    (k/= value 1)
    (debug/print "{d}" [value])))

(comment
  (main))
