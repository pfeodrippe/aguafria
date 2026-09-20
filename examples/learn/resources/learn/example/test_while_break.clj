(ns learn.example.test-while-break
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-break-test
  (let [^:var i (ak/usize 0)]
    (while true
      (if (== i 10)
        (ak/break))
      (ak/+= i 1))
    (try (testing/expectEqual 10 i))))

(comment
  (while-break-test))
