(ns learn.example.test-blocks
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest variable-outside-block-test
  (let [x (k/var 1 :i32)]
    (k/= :_ (k/& x)))
  (k/+= x 1))

(comment
  (variable-outside-block-test))
