(ns learn.example.test-namespaced-container-level-variable
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest namespaced-state-test
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(az/defstruct S
  [(az/var-decl value :i32 1234)])

(az/defn- foo :i32
  []
  (ak/+= (az/field S :value) 1)
  (az/field S :value))

(comment
  (namespaced-state-test))
