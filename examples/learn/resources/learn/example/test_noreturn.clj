(ns learn.example.test-noreturn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- foo :void
  [[condition :bool] [b :u32]]
  (let [a (if condition
            b
            (k/return))]
    (k/= :_ a)
    (k/panic "do something with a")))

(a/deftest noreturn
  (foo false 1))

(comment
  (noreturn))
