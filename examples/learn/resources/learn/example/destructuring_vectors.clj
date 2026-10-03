(ns learn.example.destructuring-vectors
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

;; emulate punpckldq
(a/defn unpack [:vector 4 :f32]
  [[x [:vector 4 :f32]] [y [:vector 4 :f32]]]
  (let [[a c _ _] x
        [b d _ _] y]
    [a b c d]))

(a/defn main :void
  []
  (let [x (a/vector [1.0 2.0 3.0 4.0] :f32)
        y (a/vector [5.0 6.0 7.0 8.0] :f32)]
    (debug/print "{}" [(unpack x y)])))

(comment
  (main))
