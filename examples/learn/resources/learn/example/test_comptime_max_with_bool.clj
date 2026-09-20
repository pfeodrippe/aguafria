(ns learn.example.test-comptime-max-with-bool
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- maximum T
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (if (== T :bool)
    (or left right)
    (if (> left right) left right)))

(az/deftest boolean-maximum-test
  (try (testing/expectEqual true (maximum :bool false true))))

(comment
  (boolean-maximum-test))
