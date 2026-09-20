(ns learn.example.test-switch-non-exhaustive
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(ns-unmap *ns* 'Number)

(az/defenum Number
  {:argument :u8}
  [:one
   :two
   :three
   :_])

(az/deftest non-exhaustive-enum-test
  (let [number (az/field Number :one)
        result (switch number
                 (case [:.one] true)
                 (case [:.two :.three] false)
                 (case [_] false))]
    (try (testing/expect result))
    (let [is-one (switch number
                   (case [:.one] true)
                   (az/case-else false))]
      (try (testing/expect is-one)))))

(comment
  (non-exhaustive-enum-test))
