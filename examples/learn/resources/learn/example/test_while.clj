(ns learn.example.test-while
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-basic
  (let [i (k/var 0 :usize)]
    (k/while (k/< i 10)
      (k/+= i 1))
    (try (testing/expectEqual 10 i))))

(comment
  (while-basic))
