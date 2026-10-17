(ns learn.example.test-coerce-to-error-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest coercion-to-error-unions
  (let [x (as 1234 [:error-union :anyerror :i32])
        y (as (a/error-value :Failure) [:error-union :anyerror :i32])]
    (k/try (testing/expectEqual 1234 (k/try x)))
    (k/try (testing/expectError (a/error-value :Failure) y))))

(comment
  (coercion-to-error-unions))
