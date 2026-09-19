(ns learn.examples.idiomatic-metaprogramming.comptime-mismatched-type
  (:require [aguafria.zig :as az]))

(az/defn- maximum T
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (if (> left right) left right))

(az/deftest cannot-order-booleans-test
  (set! _ (maximum :bool true false)))
