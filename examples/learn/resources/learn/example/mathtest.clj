(ns learn.example.mathtest
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn add :i32
  {:attrs #{export}}
  [[a :i32] [b :i32]]
  (+ a b))

(comment
  (add 12 34))
