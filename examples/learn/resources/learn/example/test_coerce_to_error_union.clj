(ns learn.example.test-coerce-to-error-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union-coercion-test
  (let [success (ak/as 1234 [:error-union :anyerror :i32])
        failure (ak/as (az/error-value :Failure) [:error-union :anyerror :i32])]
    (try (testing/expectEqual 1234 (try success)))
    (try (testing/expectError (az/error-value :Failure) failure))))

(comment
  (error-union-coercion-test))
