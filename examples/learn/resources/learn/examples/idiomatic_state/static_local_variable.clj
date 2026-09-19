(ns learn.examples.idiomatic-state.static-local-variable
  "Converted from test_static_local_variable.zig"
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
