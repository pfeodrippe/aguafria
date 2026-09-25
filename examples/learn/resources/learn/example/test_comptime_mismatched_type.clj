(ns learn.example.test-comptime-mismatched-type
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (if (k/> left right) left right))

(az/deftest cannot-order-booleans-test
  (k/= :_ (max :bool true false)))

(comment
  (cannot-order-booleans-test))
