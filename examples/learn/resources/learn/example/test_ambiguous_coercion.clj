(ns learn.example.test-ambiguous-coercion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; Compile time coercion of float to int
(a/deftest implicit-cast-to-comptime_int
  (let [f (f32 (/ 54.0 5))]
    (= :_ f)))

(comment
  (implicit-cast-to-comptime_int))
