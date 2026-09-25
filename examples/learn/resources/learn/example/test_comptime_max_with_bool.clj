(ns learn.example.test-comptime-max-with-bool
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{ak/comptime}} :type] [left T] [right T]]
  (if (ak/== T :bool)
    (or left right)
    (if (> left right) left right)))

(az/deftest boolean-maximum-test
  (try (testing/expectEqual true (max :bool false true))))

(comment
  (boolean-maximum-test))
