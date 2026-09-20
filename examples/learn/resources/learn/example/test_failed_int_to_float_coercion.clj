(ns learn.example.test-failed-int-to-float-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest lossy-integer-to-float-test
  (let [integer (ak/var 123 :u25)]
    (ak/= :_ (& integer))
    ;; Intentionally invalid: f32 cannot exactly represent every runtime u25.
    (let [floating (ak/f32 integer)]
      (ak/= :_ floating))))

(comment
  (lossy-integer-to-float-test))
