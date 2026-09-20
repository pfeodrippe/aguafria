(ns learn.example.test-static-local-variable
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest static-local-state-test
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(az/defn- foo :i32
  []
  (let [State (az/struct
                [(az/var-decl value :i32 1234)])]
    (ak/+= (az/field State :value) 1)
    (az/field State :value)))

(comment
  (static-local-state-test))
