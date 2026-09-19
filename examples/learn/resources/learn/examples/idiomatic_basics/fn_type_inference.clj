(ns learn.examples.idiomatic-basics.fn-type-inference
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add-forty-two (ak/TypeOf x)
  [[x :anytype]]
  (+ x 42))

(az/deftest function-type-inference-test
  (try (testing/expectEqual 43 (add-forty-two 1)))
  (try (testing/expectEqual :comptime_int (ak/TypeOf (add-forty-two 1))))
  (let [^{:zig/type :i64} y 2]
    (try (testing/expectEqual 44 (add-forty-two y)))
    (try (testing/expectEqual :i64 (ak/TypeOf (add-forty-two y))))))
