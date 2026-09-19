(ns learn.examples.idiomatic-state.static-local-variable
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest static-local-state-test
  (try (testing/expectEqual 1235 (increment-state)))
  (try (testing/expectEqual 1236 (increment-state))))

(az/defn- increment-state :i32
  []
  (let [State (az/container {:kind :struct}
                (az/var-decl value :i32 1234))]
    (ak/+= (az/field State :value) 1)
    (az/field State :value)))
