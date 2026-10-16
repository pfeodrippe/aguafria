(ns learn.example.test-fn-type-inference
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- add-forty-two (k/TypeOf x)
  [[x :anytype]]
  (k/+ x 42))

(a/deftest fn-type-inference
  (k/try (testing/expectEqual 43 (add-forty-two 1)))
  (k/try (testing/expectEqual :comptime_int (k/TypeOf (add-forty-two 1))))
  (let [y (k/i64 2)]
    (k/try (testing/expectEqual 44 (add-forty-two y)))
    (k/try (testing/expectEqual :i64 (k/TypeOf (add-forty-two y))))))

(comment
  (fn-type-inference))
