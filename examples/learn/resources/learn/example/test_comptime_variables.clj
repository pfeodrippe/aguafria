(ns learn.example.test-comptime-variables
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest comptime-vars
  (let [x (k/var 1 :i32)
        y (k/var 1 :i32 {:attrs #{:comptime}})]
    (+= x 1)
    (+= y 1)
    (k/try (testing/expectEqual 2 x))
    (k/try (testing/expectEqual 2 y))

    (when (!= y 2)
      ;; This compile error never triggers because y is a comptime variable,
      ;; and so `y != 2` is a comptime value, and this if is statically evaluated.
      (compileError "wrong y value"))))

(comment
  (comptime-vars))
