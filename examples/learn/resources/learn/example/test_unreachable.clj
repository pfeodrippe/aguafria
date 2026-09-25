(ns learn.example.test-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; unreachable is used to assert that control flow will never reach a
;; particular location:
(az/deftest basic-math-test
  (let [x 1
        y 2]
    (when (k/!= (k/+ x y) 3)
      (k/unreachable))))

(comment
  (basic-math-test))
