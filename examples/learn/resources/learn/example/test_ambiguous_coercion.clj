(ns learn.example.test-ambiguous-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; Compile time coercion of float to int
(az/deftest implicit-cast-to-comptime_int
  (let [f (k/f32 (k// 54.0 5))]
    (k/= :_ f)))

(comment
  (implicit-cast-to-comptime_int))
