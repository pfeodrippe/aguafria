(ns learn.examples.idiomatic-basics.while-continue-expression
  "Converted from test_while_continue_expression.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest continue-expression-test
  (let [^{:var :usize} i 0]
    (az/while-loop {:continue (az/assign-expr "+=" i 1)}
      (< i 10))
    (try (testing/expectEqual 10 i))))

(az/deftest compound-continue-expression-test
  (let [^{:var :usize} i 1
        ^{:var :usize} j 1]
    (az/while-loop {:continue (az/block
                               (ak/*= i 2)
                               (ak/*= j 3))}
      (< (* i j) 2000)
      (let [my-ij (* i j)]
        (try (testing/expect (< my-ij 2000)))))))
