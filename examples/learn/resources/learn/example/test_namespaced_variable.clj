(ns learn.example.test-namespaced-variable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct S
  [[:x {:var 1234} :i32]])

(a/defn- foo :i32
  []
  (+= (:x S) 1)
  (:x S))

(a/deftest namespaced-container-level-variable
  (k/try (testing/expectEqual 1235 (foo)))
  (k/try (testing/expectEqual 1236 (foo))))

(comment
  (namespaced-container-level-variable))
