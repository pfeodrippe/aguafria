(ns learn.example.test-while-continue
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-continue-test
  (let [i (k/var 0 :usize)]
    (k/while true
      (k/+= i 1)
      (if (k/< i 10)
        (k/continue))
      (k/break))
    (try (testing/expectEqual 10 i))))

(comment
  (while-continue-test))
