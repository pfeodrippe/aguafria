(ns learn.example.test-enum-literals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest enum-literals-test
  (let [inferred (k/as :.auto Color)
        explicit (az/field Color :auto)]
    (try (testing/expectEqual inferred explicit))))

(az/deftest enum-literal-switch-test
  (let [color (az/field Color :on)
        enabled (k/switch color
                  (case [:.auto] false)
                  (case [:.on] true)
                  (case [:.off] false))]
    (try (testing/expect enabled))))

(comment
  (enum-literals-test)
  (enum-literal-switch-test))
