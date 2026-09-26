(ns learn.example.test-enum-literals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest enum-literals
  (let [color1 (k/as :.auto Color)
        color2 (:auto Color)]
    (try (testing/expectEqual color1 color2))))

(az/deftest switch-using-enum-literals
  (let [color (:on Color)
        result (k/switch color
                         (case [:.auto] false)
                         (case [:.on] true)
                         (case [:.off] false))]
    (try (testing/expect result))))

(comment
  (enum-literals)
  (switch-using-enum-literals))
