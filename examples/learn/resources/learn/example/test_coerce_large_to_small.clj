(ns learn.example.test-coerce-large-to-small
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest coercing-large-integer-type-to-smaller-one-when-value-is-comptime-known-to-fit
  (let [x (u64 255)
        y (u8 x)]
    (k/try (testing/expectEqual 255 y))))

(comment
  (coercing-large-integer-type-to-smaller-one-when-value-is-comptime-known-to-fit))
