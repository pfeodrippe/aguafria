(ns learn.example.test-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

;; unreachable is used to assert that control flow will never reach a
;; particular location:
(a/deftest basic-math
  (let [x 1
        y 2]
    (when (k/!= (k/+ x y) 3)
      (k/unreachable))))

(comment
  (basic-math))
