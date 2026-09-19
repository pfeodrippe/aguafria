(ns learn.examples.idiomatic-metaprogramming.switch-non-exhaustive
  "Converted from test_switch_non-exhaustive.zig"
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst NumberTag
  (az/container {:kind :enum :argument :u8}
    (az/enum-field-decl :one)
    (az/enum-field-decl :two)
    (az/enum-field-decl :three)
    (az/enum-field-decl :_)))

(az/deftest non-exhaustive-enum-test
  (let [number (az/field NumberTag :one)
        result (switch number
                 (case [:.one] true)
                 (case [:.two :.three] false)
                 (case [_] false))]
    (try (testing/expect result))
    (let [is-one (switch number
                   (case [:.one] true)
                   (az/case-else false))]
      (try (testing/expect is-one)))))
