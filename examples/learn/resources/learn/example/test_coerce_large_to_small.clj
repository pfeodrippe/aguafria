(ns learn.example.test-coerce-large-to-small
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest coercing-large-integer-type-to-smaller-one-when-value-is-comptime-known-to-fit
  (let [x (k/u64 255)
        y (k/u8 x)]
    (try (testing/expectEqual 255 y))))

(comment
  (coercing-large-integer-type-to-smaller-one-when-value-is-comptime-known-to-fit))
