(ns learn.example.test-while-continue-expression
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest continue-expression-test
  (let [i (ak/var 0 :usize)]
    (az/while-loop {:continue (az/assign-expr "+=" i 1)}
      (< i 10))
    (try (testing/expectEqual 10 i))))

(az/deftest compound-continue-expression-test
  (let [i (ak/var 1 :usize)
        j (ak/var 1 :usize)]
    (az/while-loop {:continue (az/block
                                (ak/*= i 2)
                                (ak/*= j 3))}
      (< (* i j) 2000)
      (let [my-ij (* i j)]
        (try (testing/expect (< my-ij 2000)))))))

(comment
  (continue-expression-test)
  (compound-continue-expression-test))
