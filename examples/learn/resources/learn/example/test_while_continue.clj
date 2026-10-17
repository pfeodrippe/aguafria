(ns learn.example.test-while-continue
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest while-continue
  (let [i (k/var 0 :usize)]
    (while true
      (+= i 1)
      (if (< i 10)
        (continue))
      (break))
    (k/try (testing/expectEqual 10 i))))

(comment
  (while-continue))
