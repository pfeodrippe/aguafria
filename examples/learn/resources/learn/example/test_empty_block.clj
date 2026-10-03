(ns learn.example.test-empty-block
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest empty-block-test
  (let [a (a/block)]
    (try (testing/expectEqual :void (k/TypeOf a)))))

(comment
  (empty-block-test))
