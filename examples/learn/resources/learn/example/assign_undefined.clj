(ns learn.example.assign-undefined
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [x (k/var undefined :i32)]
    (= x 1)
    (debug/print "{d}" [x])))

(comment
  (main))
