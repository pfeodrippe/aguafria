(ns learn.example.test-unresolved-comptime-value
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (if (k/> a b) a b))

(az/defn- foo :void [[condition :bool]]
  (let [result (max (if condition :f32 :u64) 1234 5678)]
    (k/= :_ result)))

(az/deftest try-to-pass-a-runtime-type
  (foo false))

(comment
  (try-to-pass-a-runtime-type))
