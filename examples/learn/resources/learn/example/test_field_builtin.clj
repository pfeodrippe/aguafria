(ns learn.example.test-field-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point
  [[:x :u32]
   [:y :u32]
   [:z {:var 1} :u32]])

(az/deftest field-access-by-string
  (let [p (k/var (Point {:x 0 :y 0}))]
    (k/= (k/field p "x") 4)
    (k/= (k/field p "y") (k/+ (k/field p "x") 1))

    (try (testing/expectEqual 4 (k/field p "x")))
    (try (testing/expectEqual 5 (k/field p "y")))))

(az/deftest decl-access-by-string
  (try (testing/expectEqual 1 (k/field Point "z")))
  (k/= (k/field Point "z") 2)
  (try (testing/expectEqual 2 (k/field Point "z"))))

(comment
  (field-access-by-string)
  (decl-access-by-string))
