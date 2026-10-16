(ns learn.example.test-coerce-optionals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest coerce-to-optionals
  (let [x (k/as 1234 [:optional :i32])
        y (k/as nil [:optional :i32])]
    (k/try (testing/expectEqual 1234 (a/unwrap x)))
    (k/try (testing/expectEqual nil y))))

(comment
  (coerce-to-optionals))
