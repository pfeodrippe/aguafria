(ns learn.example.test-tuples
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest tuple
  (let [values (++
                [(as 1234 :u32) (as 12.34 :f64) true "hi"]
                [false false])]
    (k/try (testing/expectEqual 1234 (a/get values 0)))
    (k/try (testing/expectEqual false (a/get values 4)))
    (a/inline-for [v values i (a/range 0)]
                  (when (!= i 2)
                    (continue))
                  (k/try (testing/expect v)))
    (k/try (testing/expectEqual 6 (:len values)))
    (k/try (testing/expectEqual \h (a/get-in values [:3 0])))))

(comment
  (tuple))
