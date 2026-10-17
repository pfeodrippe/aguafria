(ns learn.example.test-locally-scoped-global-variable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- foo :i32
  []
  (let [S (a/struct [[:x {:var 1234} :i32]])]
    (+= (:x S) 1)
    (:x S)))

(a/deftest static-local-variable
  (k/try (testing/expectEqual 1235 (foo)))
  (k/try (testing/expectEqual 1236 (foo))))

(comment
  (static-local-variable))
