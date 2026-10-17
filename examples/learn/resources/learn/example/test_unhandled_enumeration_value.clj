(ns learn.example.test-unhandled-enumeration-value
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defenum Color
  [:auto
   :off
   :on])

(a/deftest exhaustive-switching
  (let [color (:off Color)]
    (switch color
      (:auto Color) (a/block)
      (:on Color) (a/block))))

(comment
  (exhaustive-switching))
