(ns learn.example.test-static-local-variable
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- foo :i32
  []
  (let [S (az/struct [[:x {:var 1234} :i32]])]
    (k/+= (:x S) 1)
    (:x S)))

(az/deftest static-local-variable
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(comment
  (static-local-variable))
