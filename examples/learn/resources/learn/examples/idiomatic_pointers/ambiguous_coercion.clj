(ns learn.examples.idiomatic-pointers.ambiguous-coercion
  (:require [aguafria.zig :as az]))

(az/deftest ambiguous-comptime-coercion-test
  ;; Intentionally invalid: peer coercion selects comptime_int for the division.
  (let [^{:zig/type :f32} quotient (/ 54.0 5)]
    (set! _ quotient)))
