(ns learn.examples.idiomatic-pointers.failed-int-to-float-coercion
  "Converted from test_failed_int_to_float_coercion.zig"
  (:require [aguafria.zig :as az]))

(az/deftest lossy-integer-to-float-test
  (let [^{:var :u25} integer 123]
    (set! _ (& integer))
    ;; Intentionally invalid: f32 cannot exactly represent every runtime u25.
    (let [^{:zig/type :f32} floating integer]
      (set! _ floating))))
