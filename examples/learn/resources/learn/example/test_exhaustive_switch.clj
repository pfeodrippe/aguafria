(ns learn.example.test-exhaustive-switch
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest exhaustive-enum-switch-test
  (let [color (az/field Color :off)
        disabled (k/switch color
                   (case [:.auto] false)
                   (case [:.on] false)
                   (case [:.off] true))]
    (try (testing/expect disabled))))

(comment
  (exhaustive-enum-switch-test))
