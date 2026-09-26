(ns learn.example.test-defer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- defer-example :!usize
  []
  (let [a (k/var 1 :usize)]
    (az/block
     (k/defer (k/= a 2))
     (k/= a 1))
    (try (testing/expectEqual 2 a))
    (k/= a 5)
    a))

(az/deftest defer-basics
  (try (testing/expectEqual 5 (try (defer-example)))))

(comment
  (defer-basics))
