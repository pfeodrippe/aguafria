(ns learn.example.test-comptime-mismatched-type
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (if (k/> a b) a b))

(az/deftest try-to-compare-bools
  (k/= :_ (max :bool true false)))

(comment
  (try-to-compare-bools))
