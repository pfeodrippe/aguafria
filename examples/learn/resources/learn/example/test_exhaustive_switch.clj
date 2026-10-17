(ns learn.example.test-exhaustive-switch
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Color
  [:auto
   :off
   :on])

(a/deftest enum-literals-with-switch
  (let [color (:off Color)
        result (switch color
                 :auto false
                 :on false
                 :off true)]
    (k/try (testing/expect result))))

(comment
  (enum-literals-with-switch))
