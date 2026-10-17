(ns learn.example.runtime-vs-comptime
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- divide :i32
  [[a :i32] [b :i32]]
  (/ a b))

(comment
  (divide 12 3))
