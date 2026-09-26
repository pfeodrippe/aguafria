(ns learn.example.assign-undefined
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [x (k/var k/undefined :i32)]
    (k/= x 1)
    (debug/print "{d}" [x])))

(comment
  (main))
