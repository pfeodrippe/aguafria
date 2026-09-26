(ns learn.example.test-noreturn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- foo :void
  [[condition :bool] [b :u32]]
  (let [a (if condition
            b
            (k/return))]
    (k/= :_ a)
    (k/panic "do something with a")))

(az/deftest noreturn
  (foo false 1))

(comment
  (noreturn))
