(ns learn.examples.idiomatic-basics.labeled-break
  "Converted from test_labeled_break.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest labeled-break-test
  (let [^{:var :i32} y 123
        x (az/labeled-block blk
            (ak/+= y 1)
            (ak/break blk y))]
    (try (testing/expectEqual 124 x))
    (try (testing/expectEqual 124 y))))
