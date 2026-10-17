(ns learn.example.test-while
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest while-basic
  (let [i (k/var 0 :usize)]
    (while (< i 10)
      (+= i 1))
    (k/try (testing/expectEqual 10 i))))

(comment
  (while-basic))
