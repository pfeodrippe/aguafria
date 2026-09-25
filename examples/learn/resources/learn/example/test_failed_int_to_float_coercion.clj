(ns learn.example.test-failed-int-to-float-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest lossy-integer-to-float-test
  (let [integer (k/var 123 :u25)]
    (k/= :_ (k/& integer))
    ;; Intentionally invalid: f32 cannot exactly represent every runtime u25.
    (let [floating (k/f32 integer)]
      (k/= :_ floating))))

(comment
  (lossy-integer-to-float-test))
