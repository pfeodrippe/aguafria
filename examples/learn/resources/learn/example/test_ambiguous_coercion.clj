(ns learn.example.test-ambiguous-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest ambiguous-comptime-coercion-test
  ;; Intentionally invalid: peer coercion selects comptime_int for the division.
  (let [quotient (k/f32 (k// 54.0 5))]
    (k/= :_ quotient)))

(comment
  (ambiguous-comptime-coercion-test))
