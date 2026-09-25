(ns learn.example.test-namespaced-container-level-variable
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct S
  [[:value {:var 1234} :i32]])

(az/defn- foo :i32
  []
  (k/+= (az/field S :value) 1)
  (az/field S :value))

(az/deftest namespaced-state-test
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(comment
  (namespaced-state-test))
