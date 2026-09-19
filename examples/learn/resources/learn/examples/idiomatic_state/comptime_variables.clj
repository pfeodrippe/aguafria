(ns learn.examples.idiomatic-state.comptime-variables
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-variables-test
  (let [^{:var :i32} x 1
        ^{:var :i32 :zig/prefix "comptime"} y 1]
    (ak/+= x 1)
    (ak/+= y 1)
    (try (testing/expectEqual 2 x))
    (try (testing/expectEqual 2 y))

    (when (!= y 2)
      ;; This compile error never triggers: y is a comptime variable, so
      ;; y != 2 is a comptime value and the condition is statically evaluated.
      (ak/compileError "wrong y value"))))
