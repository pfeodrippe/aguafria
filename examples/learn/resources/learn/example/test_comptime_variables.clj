(ns learn.example.test-comptime-variables
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-variables-test
  (let [x (k/var 1 :i32)
        y (k/var 1 :i32 {:attrs #{k/comptime}})]
    (k/+= x 1)
    (k/+= y 1)
    (try (testing/expectEqual 2 x))
    (try (testing/expectEqual 2 y))

    (when (k/!= y 2)
      ;; This compile error never triggers: y is a comptime variable, so
      ;; y != 2 is a comptime value and the condition is statically evaluated.
      (k/compileError "wrong y value"))))

(comment
  (comptime-variables-test))
