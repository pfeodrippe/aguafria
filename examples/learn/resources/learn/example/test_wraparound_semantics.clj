(ns learn.example.test-wraparound-semantics
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest wraparound-addition-and-subtraction
  (let [x (i32 (math/maxInt :i32))
        min-val (+% x 1)]
    (k/try (testing/expectEqual (math/minInt :i32) min-val))
    (let [max-val (-% min-val 1)]
      (k/try (testing/expectEqual (math/maxInt :i32) max-val)))))

(comment
  (wraparound-addition-and-subtraction))
