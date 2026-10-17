(ns learn.example.test-empty-block
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest empty-block-test
  (let [a (a/block)]
    (k/try (testing/expectEqual :void (TypeOf a)))))

(comment
  (empty-block-test))
