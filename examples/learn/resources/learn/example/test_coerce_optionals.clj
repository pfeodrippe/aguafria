(ns learn.example.test-coerce-optionals
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest coerce-to-optionals
  (let [x (as 1234 [:optional :i32])
        y (as nil [:optional :i32])]
    (k/try (testing/expectEqual 1234 (a/unwrap x)))
    (k/try (testing/expectEqual nil y))))

(comment
  (coerce-to-optionals))
