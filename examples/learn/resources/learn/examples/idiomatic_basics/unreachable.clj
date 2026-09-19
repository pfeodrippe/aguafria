(ns learn.examples.idiomatic-basics.unreachable
  "Converted from test_unreachable.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; unreachable is used to assert that control flow will never reach a
;; particular location:
(az/deftest basic-math-test
  (let [x 1
        y 2]
    (when (ak/!= (+ x y) 3)
      (ak/unreachable))))
