(ns learn.example.test-while-break
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest while-break
  (let [i (k/var 0 :usize)]
    (while true
      (if (== i 10)
        (break))
      (+= i 1))
    (k/try (testing/expectEqual 10 i))))

(comment
  (while-break))
