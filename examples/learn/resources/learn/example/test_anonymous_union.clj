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

(az/deftest anonymous-union-literal-test
  (let [integer (Number {:int 42})
        floating (make-number)]
    (try (testing/expectEqual 42 (:int integer)))
    (try (testing/expectEqual 12.34 (:float floating)))))

(comment
  (anonymous-union-literal-test))
