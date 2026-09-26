(ns learn.example.test-exhaustive-switch
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest enum-literals-with-switch
  (let [color (:off Color)
        result (k/switch color
                         (case [:.auto] false)
                         (case [:.on] false)
                         (case [:.off] true))]
    (try (testing/expect result))))

(comment
  (enum-literals-with-switch))
