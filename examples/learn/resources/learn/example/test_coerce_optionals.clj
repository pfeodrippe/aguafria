(ns learn.example.test-coerce-optionals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-coercion-test
  (let [present (k/as 1234 [:optional :i32])
        absent (k/as nil [:optional :i32])]
    (try (testing/expectEqual 1234 (az/unwrap present)))
    (try (testing/expectEqual nil absent))))

(comment
  (optional-coercion-test))
