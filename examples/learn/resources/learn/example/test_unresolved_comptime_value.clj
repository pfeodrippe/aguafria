(ns learn.example.test-unresolved-comptime-value
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{ak/comptime}} :type] [left T] [right T]]
  (if (> left right) left right))

(az/defn- foo :void [[condition :bool]]
  (let [result (max (if condition :f32 :u64) 1234 5678)]
    (ak/= :_ result)))

(az/deftest runtime-type-is-not-comptime-test
  (foo false))

(comment
  (runtime-type-is-not-comptime-test))
