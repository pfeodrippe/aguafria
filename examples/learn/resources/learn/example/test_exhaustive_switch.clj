(ns learn.example.test-exhaustive-switch
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Color
  (az/container {:kind :enum}
    (az/enum-field-decl :auto)
    (az/enum-field-decl :off)
    (az/enum-field-decl :on)))

(az/deftest exhaustive-enum-switch-test
  (let [color (az/field Color :off)
        disabled (ak/switch color
                   (case [:.auto] false)
                   (case [:.on] false)
                   (case [:.off] true))]
    (try (testing/expect disabled))))

(comment
  (exhaustive-enum-switch-test))
