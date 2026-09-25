(ns learn.example.test-comptime-max-with-bool
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (if (k/== T :bool)
    (or left right)
    (if (k/> left right) left right)))

(az/deftest boolean-maximum-test
  (try (testing/expectEqual true (max :bool false true))))

(comment
  (boolean-maximum-test))
