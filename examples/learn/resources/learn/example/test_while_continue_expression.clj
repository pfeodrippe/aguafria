(ns learn.example.test-while-continue-expression
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest while-loop-continue-expression
  (let [i (k/var 0 :usize)]
    (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                  (< i 10))
    (k/try (testing/expectEqual 10 i))))

(a/deftest while-loop-continue-expression-more-complicated
  (let [i (k/var 1 :usize)
        j (k/var 1 :usize)]
    (a/while-loop {:continue (a/block
                              (*= i 2)
                              (*= j 3))}
                  (< (* i j) 2000)
                  (let [my-ij (* i j)]
                    (k/try (testing/expect (< my-ij 2000)))))))

(comment
  (while-loop-continue-expression)
  (while-loop-continue-expression-more-complicated))
