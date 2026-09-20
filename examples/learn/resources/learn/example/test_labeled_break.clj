(ns learn.example.test-labeled-break
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest labeled-break-test
  (let [y (ak/var 123 :i32)
        x (az/labeled-block blk
            (ak/+= y 1)
            (ak/break blk y))]
    (try (testing/expectEqual 124 x))
    (try (testing/expectEqual 124 y))))

(comment
  (labeled-break-test))
