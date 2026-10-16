(ns learn.example.test-tuples
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest tuple
  (let [values (k/++
                [(k/as 1234 :u32) (k/as 12.34 :f64) true "hi"]
                [false false])]
    (k/try (testing/expectEqual 1234 (a/get values 0)))
    (k/try (testing/expectEqual false (a/get values 4)))
    (a/inline-for [v values i (a/range 0)]
                  (when (k/!= i 2)
                    (k/continue))
                  (k/try (testing/expect v)))
    (k/try (testing/expectEqual 6 (:len values)))
    (k/try (testing/expectEqual \h (a/get-in values [:3 0])))))

(comment
  (tuple))
