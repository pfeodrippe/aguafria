(ns learn.example.test-unresolved-comptime-value
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (if (k/> left right) left right))

(az/defn- foo :void [[condition :bool]]
  (let [result (max (if condition :f32 :u64) 1234 5678)]
    (k/= :_ result)))

(az/deftest runtime-type-is-not-comptime-test
  (foo false))

(comment
  (runtime-type-is-not-comptime-test))
