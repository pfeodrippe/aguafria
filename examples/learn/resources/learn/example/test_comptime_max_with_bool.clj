(ns learn.example.test-comptime-max-with-bool
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- max T
  [[T {:attrs #{comptime}} :type] [a T] [b T]]
  (if (== T :bool)
    (or a b)
    (if (> a b) a b)))

(a/deftest try-to-compare-bools
  (k/try (testing/expectEqual true (max :bool false true))))

(comment
  (try-to-compare-bools))
