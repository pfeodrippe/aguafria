(ns learn.example.test-namespaced-variable
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct S
  [[:x {:var 1234} :i32]])

(a/defn- foo :i32
  []
  (k/+= (:x S) 1)
  (:x S))

(a/deftest namespaced-container-level-variable
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(comment
  (namespaced-container-level-variable))
