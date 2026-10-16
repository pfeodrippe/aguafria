(ns learn.example.test-defer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- defer-example :!usize
  []
  (let [a (k/var 1 :usize)]
    (a/block
     (k/defer (k/= a 2))
     (k/= a 1))
    (k/try (testing/expectEqual 2 a))
    (k/= a 5)
    a))

(a/deftest defer-basics
  (k/try (testing/expectEqual 5 (k/try (defer-example)))))

(comment
  (defer-basics))
