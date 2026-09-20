(ns learn.example.test-blocks
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest variable-outside-block-test
  (let [x (ak/var 1 :i32)]
    (ak/= :_ (& x)))
  (ak/+= x 1))

(comment
  (variable-outside-block-test))
