(ns learn.example.test-noreturn
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- foo :void
  [[condition :bool] [b :u32]]
  (let [a (if condition
            b
            (return))]
    (= :_ a)
    (panic "do something with a")))

(a/deftest noreturn
  (foo false 1))

(comment
  (noreturn))
