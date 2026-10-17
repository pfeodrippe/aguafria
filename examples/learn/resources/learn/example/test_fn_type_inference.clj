(ns learn.example.test-fn-type-inference
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- add-forty-two (TypeOf x)
  [[x :anytype]]
  (+ x 42))

(a/deftest fn-type-inference
  (k/try (testing/expectEqual 43 (add-forty-two 1)))
  (k/try (testing/expectEqual :comptime_int (TypeOf (add-forty-two 1))))
  (let [y (i64 2)]
    (k/try (testing/expectEqual 44 (add-forty-two y)))
    (k/try (testing/expectEqual :i64 (TypeOf (add-forty-two y))))))

(comment
  (fn-type-inference))
