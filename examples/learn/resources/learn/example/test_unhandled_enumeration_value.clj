(ns learn.example.test-unhandled-enumeration-value
  (:require [aguafria.zig :as a]))

(a/defenum Color
  [:auto
   :off
   :on])

(a/deftest exhaustive-switching
  (let [color (:off Color)]
    (aguafria.keyword/switch color
      (:auto Color) (a/block)
      (:on Color) (a/block))))

(comment
  (exhaustive-switching))
