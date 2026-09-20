(ns learn.example.test-blocks
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest variable-outside-block-test
  (let [^{:var :i32} x 1]
    (set! _ (& x)))
  (ak/+= x 1))

(comment
  (variable-outside-block-test))
