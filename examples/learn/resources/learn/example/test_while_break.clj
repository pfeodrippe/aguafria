(ns learn.example.test-while-break
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-break-test
  (let [i (ak/var 0 :usize)]
    (while true
      (if (== i 10)
        (ak/break))
      (ak/+= i 1))
    (try (testing/expectEqual 10 i))))

(comment
  (while-break-test))
