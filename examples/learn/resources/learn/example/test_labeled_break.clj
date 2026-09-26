(ns learn.example.test-labeled-break
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest labeled-break-from-labeled-block-expression
  (let [y (k/var 123 :i32)
        x (az/with-block :blk
            (k/+= y 1)
            (k/break :blk y))]
    (try (testing/expectEqual 124 x))
    (try (testing/expectEqual 124 y))))

(comment
  (labeled-break-from-labeled-block-expression))
