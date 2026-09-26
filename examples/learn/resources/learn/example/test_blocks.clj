(ns learn.example.test-blocks
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest access-variable-after-block-scope
  (let [x (k/var 1 :i32)]
    (k/= :_ (k/& x)))
  (k/+= x 1))

(comment
  (access-variable-after-block-scope))
