(ns learn.example.test-anonymous-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(c/ns-unmap c/*ns* 'Number)

(a/defunion Number
  [[:int :i32]
   [:float :f64]])

(a/defn make-number Number []
  {:float 12.34})

(a/deftest anonymous-union-literal-syntax
  (let [i (Number {:int 42})
        f (make-number)]
    (k/try (testing/expectEqual 42 (:int i)))
    (k/try (testing/expectEqual 12.34 (:float f)))))

(comment
  (anonymous-union-literal-syntax))
