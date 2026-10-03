(ns learn.example.var-must-be-initialized
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [x]
    (k/= x 1)))

(comment
  (main))
