(ns learn.example.test-empty-block
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest empty-block-test
  (let [a (az/block)
        b (az/init (az/object []) :void)]
    (try (testing/expectEqual :void (ak/TypeOf a)))
    (try (testing/expectEqual :void (ak/TypeOf b)))
    (try (testing/expectEqual a b))))

(comment
  (empty-block-test))
