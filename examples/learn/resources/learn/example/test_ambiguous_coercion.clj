(ns learn.example.test-ambiguous-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest ambiguous-comptime-coercion-test
  ;; Intentionally invalid: peer coercion selects comptime_int for the division.
  (let [quotient (ak/f32 (/ 54.0 5))]
    (ak/= :_ quotient)))

(comment
  (ambiguous-comptime-coercion-test))
