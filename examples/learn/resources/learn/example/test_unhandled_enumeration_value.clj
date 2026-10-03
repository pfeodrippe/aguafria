(ns learn.example.test-unhandled-enumeration-value
  (:require [aguafria.zig :as a]))

(a/defenum Color
  [:auto
   :off
   :on])

(a/deftest exhaustive-switching
  (let [color (:off Color)]
    (a/switch-stmt color
                   (case [(:auto Color)] (a/block))
                   (case [(:on Color)] (a/block)))))

(comment
  (exhaustive-switching))
