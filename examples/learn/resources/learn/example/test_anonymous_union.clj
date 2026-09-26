(ns learn.example.test-anonymous-union
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(ns-unmap *ns* 'Number)

(az/defconst Number
  (az/union
   [[:int :i32]
    [:float :f64]]))

(az/defn make-number Number []
  {:float 12.34})

(az/deftest anonymous-union-literal-syntax
  (let [i (Number {:int 42})
        f (make-number)]
    (try (testing/expectEqual 42 (:int i)))
    (try (testing/expectEqual 12.34 (:float f)))))

(comment
  (anonymous-union-literal-syntax))
