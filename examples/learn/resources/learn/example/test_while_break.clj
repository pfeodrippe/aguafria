(ns learn.example.test-while-break
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-break
  (let [i (k/var 0 :usize)]
    (k/while true
      (if (k/== i 10)
        (k/break))
      (k/+= i 1))
    (try (testing/expectEqual 10 i))))

(comment
  (while-break))
