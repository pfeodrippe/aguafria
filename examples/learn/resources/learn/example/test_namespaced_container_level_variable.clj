(ns learn.example.test-namespaced-container-level-variable
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest namespaced-state-test
  (try (testing/expectEqual 1235 (increment-state)))
  (try (testing/expectEqual 1236 (increment-state))))

(az/defconst State
  (az/container {:kind :struct}
    (az/var-decl value :i32 1234)))

(az/defn- increment-state :i32
  []
  (ak/+= (az/field State :value) 1)
  (az/field State :value))

(comment
  (namespaced-state-test))
