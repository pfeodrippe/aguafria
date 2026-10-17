(ns learn.example.test-labeled-break
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest labeled-break-from-labeled-block-expression
  (let [y (k/var 123 :i32)
        x (a/with-block :blk
            (+= y 1)
            (break :blk y))]
    (k/try (testing/expectEqual 124 x))
    (k/try (testing/expectEqual 124 y))))

(comment
  (labeled-break-from-labeled-block-expression))
