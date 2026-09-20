(ns learn.example.test-failed-int-to-float-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest lossy-integer-to-float-test
  (let [^:var integer (ak/u25 123)]
    (set! _ (& integer))
    ;; Intentionally invalid: f32 cannot exactly represent every runtime u25.
    (let [floating (ak/f32 integer)]
      (set! _ floating))))

(comment
  (lossy-integer-to-float-test))
