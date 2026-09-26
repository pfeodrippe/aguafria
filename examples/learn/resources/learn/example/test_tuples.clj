(ns learn.example.test-tuples
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest tuple
  (let [values (k/++
                [(k/as 1234 :u32) (k/as 12.34 :f64) true "hi"]
                (k/** [false] 2))]
    (try (testing/expectEqual 1234 (az/get values 0)))
    (try (testing/expectEqual false (az/get values 4)))
    (az/inline-for [v values i (az/range 0)]
                   (when (k/!= i 2)
                     (k/continue))
                   (try (testing/expect v)))
    (try (testing/expectEqual 6 (:len values)))
    (try (testing/expectEqual \h (az/get-in values [:3 0])))))

(comment
  (tuple))
