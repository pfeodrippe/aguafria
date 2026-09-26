(ns learn.example.test-comptime-max-with-bool
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (if (k/== T :bool)
    (or a b)
    (if (k/> a b) a b)))

(az/deftest try-to-compare-bools
  (try (testing/expectEqual true (max :bool false true))))

(comment
  (try-to-compare-bools))
