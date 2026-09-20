(ns learn.example.test-struct-result
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point [[:x :i32] [:y :i32]])

(az/deftest anonymous-struct-literal-test
  ;; The constructor supplies the type of the anonymous literal.
  (let [point (Point {:x 13 :y 67})]
    (try (testing/expectEqual 13 (az/field point :x)))
    (try (testing/expectEqual 67 (az/field point :y)))))

(comment
  (anonymous-struct-literal-test))
