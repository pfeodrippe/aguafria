(ns learn.example.test-unreachable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; unreachable is used to assert that control flow will never reach a
;; particular location:
(a/deftest basic-math
  (let [x 1
        y 2]
    (when (!= (+ x y) 3)
      (unreachable))))

(comment
  (basic-math))
