(ns learn.example.test-coerce-to-error-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest coercion-to-error-unions
  (let [x (k/as 1234 [:error-union :anyerror :i32])
        y (k/as (az/error-value :Failure) [:error-union :anyerror :i32])]
    (try (testing/expectEqual 1234 (try x)))
    (try (testing/expectError (az/error-value :Failure) y))))

(comment
  (coercion-to-error-unions))
