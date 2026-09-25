(ns learn.example.test-noreturn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- foo :void
  [[condition :bool] [input :u32]]
  (let [value (if condition
                input
                (k/return))]
    (k/= :_ value)
    (k/panic "do something with a")))

(az/deftest noreturn-test
  (foo false 1))

(comment
  (noreturn-test))
