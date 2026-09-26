(ns learn.example.destructuring-vectors
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

;; emulate punpckldq
(az/defn unpack [:vector 4 :f32]
  [[x [:vector 4 :f32]] [y [:vector 4 :f32]]]
  (let [[a c _ _] x
        [b d _ _] y]
    [a b c d]))

(az/defn main :void
  []
  (let [x (k/as [1.0 2.0 3.0 4.0] [:vector 4 :f32])
        y (k/as [5.0 6.0 7.0 8.0] [:vector 4 :f32])]
    (debug/print "{}" [(unpack x y)])))

(comment
  (main))
