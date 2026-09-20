(ns learn.example.test-while-continue
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-continue-test
  (let [i (ak/var 0 :usize)]
    (while true
      (ak/+= i 1)
      (if (< i 10)
        (ak/continue))
      (ak/break))
    (try (testing/expectEqual 10 i))))

(comment
  (while-continue-test))
