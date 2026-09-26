(ns learn.example.mutable-var
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [y (k/var 5678 :i32)]
    (k/+= y 1)
    (debug/print "{d}" [y])))

(comment
  (main))
