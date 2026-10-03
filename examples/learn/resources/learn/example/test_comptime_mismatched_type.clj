(ns learn.example.test-comptime-mismatched-type
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- max T
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (if (k/> a b) a b))

(a/deftest try-to-compare-bools
  (k/= :_ (max :bool true false)))

(comment
  (try-to-compare-bools))
