(ns learn.example.test-coerce-large-to-small
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-narrowing-test
  ;; The compiler knows this particular u64 value fits in u8.
  (let [wide (k/u64 255)
        narrow (k/u8 wide)]
    (try (testing/expectEqual 255 narrow))))

(comment
  (comptime-narrowing-test))
