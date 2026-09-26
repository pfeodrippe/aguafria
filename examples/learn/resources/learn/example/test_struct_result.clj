(ns learn.example.test-struct-result
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point [[:x :i32] [:y :i32]])

(az/deftest anonymous-struct-literal
  (let [pt (Point {:x 13 :y 67})]
    (try (testing/expectEqual 13 (:x pt)))
    (try (testing/expectEqual 67 (:y pt)))))

(comment
  (anonymous-struct-literal))
