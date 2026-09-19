(ns learn.example.test-defer
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- defer-example :!usize
  []
  (let [^{:var :usize} a 1]
    (az/block
      (ak/defer (set! a 2))
      (set! a 1))
    (try (testing/expectEqual 2 a))
    (set! a 5)
    (ak/return a)))

(az/deftest defer-basics-test
  (try (testing/expectEqual 5 (try (defer-example)))))
