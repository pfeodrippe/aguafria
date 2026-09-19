(ns learn.example.test-wraparound-semantics
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest wraparound-test
  (let [^{:zig/type :i32} x (math/maxInt :i32)
        min-val (ak/+% x 1)]
    (try (testing/expectEqual (math/minInt :i32) min-val))
    (let [max-val (ak/-% min-val 1)]
      (try (testing/expectEqual (math/maxInt :i32) max-val)))))
