(ns learn.example.test-defer
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- defer-example :!usize
  []
  (let [a (ak/var 1 :usize)]
    (az/block
      (ak/defer (ak/= a 2))
      (ak/= a 1))
    (try (testing/expectEqual 2 a))
    (ak/= a 5)
    a))

(az/deftest defer-basics-test
  (try (testing/expectEqual 5 (try (defer-example)))))

(comment
  (defer-basics-test))
