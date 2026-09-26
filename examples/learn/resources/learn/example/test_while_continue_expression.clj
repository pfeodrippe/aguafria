(ns learn.example.test-while-continue-expression
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-loop-continue-expression
  (let [i (k/var 0 :usize)]
    (az/while-loop {:continue (az/assign-expr "+=" i 1)}
                   (k/< i 10))
    (try (testing/expectEqual 10 i))))

(az/deftest while-loop-continue-expression-more-complicated
  (let [i (k/var 1 :usize)
        j (k/var 1 :usize)]
    (az/while-loop {:continue (az/block
                               (k/*= i 2)
                               (k/*= j 3))}
                   (k/< (k/* i j) 2000)
                   (let [my-ij (k/* i j)]
                     (try (testing/expect (k/< my-ij 2000)))))))

(comment
  (while-loop-continue-expression)
  (while-loop-continue-expression-more-complicated))
