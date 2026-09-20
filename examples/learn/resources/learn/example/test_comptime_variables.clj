(ns learn.example.test-comptime-variables
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-variables-test
  (let [^:var x (ak/i32 1)
        ^{:zig/prefix "comptime", :var true} y (ak/i32 1)]
    (ak/+= x 1)
    (ak/+= y 1)
    (try (testing/expectEqual 2 x))
    (try (testing/expectEqual 2 y))

    (when (!= y 2)
      ;; This compile error never triggers: y is a comptime variable, so
      ;; y != 2 is a comptime value and the condition is statically evaluated.
      (ak/compileError "wrong y value"))))

(comment
  (comptime-variables-test))
