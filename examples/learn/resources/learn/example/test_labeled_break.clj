(ns learn.example.test-labeled-break
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest labeled-break-test
  (let [^:var y (ak/i32 123)
        x (az/labeled-block blk
            (ak/+= y 1)
            (ak/break blk y))]
    (try (testing/expectEqual 124 x))
    (try (testing/expectEqual 124 y))))

(comment
  (labeled-break-test))
