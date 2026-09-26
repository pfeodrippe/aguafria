(ns learn.example.test-wraparound-semantics
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest wraparound-addition-and-subtraction
  (let [x (k/i32 (math/maxInt :i32))
        min-val (k/+% x 1)]
    (try (testing/expectEqual (math/minInt :i32) min-val))
    (let [max-val (k/-% min-val 1)]
      (try (testing/expectEqual (math/maxInt :i32) max-val)))))

(comment
  (wraparound-addition-and-subtraction))
