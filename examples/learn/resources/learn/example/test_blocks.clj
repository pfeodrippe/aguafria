(ns learn.example.test-blocks
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest access-variable-after-block-scope
  (let [x (k/var 1 :i32)]
    (= :_ (k/& x)))
  (+= x 1))

(comment
  (access-variable-after-block-scope))
