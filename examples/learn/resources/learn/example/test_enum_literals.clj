(ns learn.example.test-enum-literals
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Color
  (az/container {:kind :enum}
    (az/enum-field-decl :auto)
    (az/enum-field-decl :off)
    (az/enum-field-decl :on)))

(az/deftest enum-literals-test
  (let [^{:zig/type Color} inferred :.auto
        explicit (az/field Color :auto)]
    (try (testing/expectEqual inferred explicit))))

(az/deftest enum-literal-switch-test
  (let [color (az/field Color :on)
        enabled (ak/switch color
                  (case [:.auto] false)
                  (case [:.on] true)
                  (case [:.off] false))]
    (try (testing/expect enabled))))
