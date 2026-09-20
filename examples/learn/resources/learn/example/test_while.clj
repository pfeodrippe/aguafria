(ns learn.example.test-while
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-basic-test
  (let [i (ak/var 0 :usize)]
    (while (< i 10)
      (ak/+= i 1))
    (try (testing/expectEqual 10 i))))

(comment
  (while-basic-test))
