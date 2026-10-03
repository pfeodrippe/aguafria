(ns learn.example.test-locally-scoped-global-variable
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- foo :i32
  []
  (let [S (a/struct [[:x {:var 1234} :i32]])]
    (k/+= (:x S) 1)
    (:x S)))

(a/deftest static-local-variable
  (try (testing/expectEqual 1235 (foo)))
  (try (testing/expectEqual 1236 (foo))))

(comment
  (static-local-variable))
