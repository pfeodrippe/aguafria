(ns learn.example.test-defer
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- defer-example :!usize
  []
  (let [a (k/var 1 :usize)]
    (a/block
     (defer (= a 2))
     (= a 1))
    (k/try (testing/expectEqual 2 a))
    (= a 5)
    a))

(a/deftest defer-basics
  (k/try (testing/expectEqual 5 (k/try (defer-example)))))

(comment
  (defer-basics))
