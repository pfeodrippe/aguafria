(ns learn.example.test-field-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point
  [[:x :u32]
   [:y :u32]
   [:z {:var 1} :u32]])

(az/deftest string-field-access-test
  (let [point (k/var (Point {:x 0 :y 0}))]
    (k/= (k/field point "x") 4)
    (k/= (k/field point "y") (k/+ (k/field point "x") 1))

    (try (testing/expectEqual 4 (k/field point "x")))
    (try (testing/expectEqual 5 (k/field point "y")))))

(az/deftest string-declaration-access-test
  (try (testing/expectEqual 1 (k/field Point "z")))
  (k/= (k/field Point "z") 2)
  (try (testing/expectEqual 2 (k/field Point "z"))))

(comment
  (string-field-access-test)
  (string-declaration-access-test))
