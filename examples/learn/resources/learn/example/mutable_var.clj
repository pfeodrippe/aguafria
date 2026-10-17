(ns learn.example.mutable-var
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [y (k/var 5678 :i32)]
    (+= y 1)
    (debug/print "{d}" [y])))

(comment
  (main))
