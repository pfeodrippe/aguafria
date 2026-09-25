(ns learn.example.test-static-local-variable
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- foo :i32
  []
  (let [State (az/struct [[:value {:var 1234} :i32]])]
    (k/+= (az/field State :value) 1)
    (az/field State :value)))

(az/deftest static-local-state-test
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(comment
  (static-local-state-test))
