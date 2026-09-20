(ns learn.example.test-comptime-mismatched-type
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (if (> left right) left right))

(az/deftest cannot-order-booleans-test
  (ak/= :_ (max :bool true false)))

(comment
  (cannot-order-booleans-test))
