(ns learn.example.test-field-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Point
  [[:x :u32]
   [:y :u32]
   [:z {:var 1} :u32]])

(a/deftest field-access-by-string
  (let [p (k/var (Point {:x 0 :y 0}))]
    (= (field p "x") 4)
    (= (field p "y") (+ (field p "x") 1))

    (k/try (testing/expectEqual 4 (field p "x")))
    (k/try (testing/expectEqual 5 (field p "y")))))

(a/deftest decl-access-by-string
  (k/try (testing/expectEqual 1 (field Point "z")))
  (= (field Point "z") 2)
  (k/try (testing/expectEqual 2 (field Point "z"))))

(comment
  (field-access-by-string)
  (decl-access-by-string))
