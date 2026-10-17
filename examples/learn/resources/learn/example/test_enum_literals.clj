(ns learn.example.test-enum-literals
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Color
  [:auto
   :off
   :on])

(a/deftest enum-literals
  (let [color1 (as :auto Color)
        color2 (:auto Color)]
    (k/try (testing/expectEqual color1 color2))))

(a/deftest switch-using-enum-literals
  (let [color (:on Color)
        result (switch color
                 :auto false
                 :on true
                 :off false)]
    (k/try (testing/expect result))))

(comment
  (enum-literals)
  (switch-using-enum-literals))
