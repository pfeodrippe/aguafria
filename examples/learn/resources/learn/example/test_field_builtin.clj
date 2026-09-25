(ns learn.example.test-field-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point
  [[:x :u32]
   [:y :u32]
   [:z {:var 1} :u32]])

(az/deftest string-field-access-test
  (let [point (ak/var (Point {:x 0 :y 0}))]
    (ak/= (ak/field point "x") 4)
    (ak/= (ak/field point "y") (+ (ak/field point "x") 1))

    (try (testing/expectEqual 4 (ak/field point "x")))
    (try (testing/expectEqual 5 (ak/field point "y")))))

(az/deftest string-declaration-access-test
  (try (testing/expectEqual 1 (ak/field Point "z")))
  (ak/= (ak/field Point "z") 2)
  (try (testing/expectEqual 2 (ak/field Point "z"))))

(comment
  (string-field-access-test)
  (string-declaration-access-test))
