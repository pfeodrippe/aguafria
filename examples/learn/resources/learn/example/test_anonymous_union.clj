(ns learn.example.test-anonymous-union
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(ns-unmap *ns* 'Number)

(a/defunion Number
  [[:int :i32]
   [:float :f64]])

(a/defn make-number Number []
  {:float 12.34})

(a/deftest anonymous-union-literal-syntax
  (let [i (Number {:int 42})
        f (make-number)]
    (try (testing/expectEqual 42 (:int i)))
    (try (testing/expectEqual 12.34 (:float f)))))

(comment
  (anonymous-union-literal-syntax))
