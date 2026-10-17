(ns learn.example.test-setEvalBranchQuota-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest foo
  (comptime
   (do
     (setEvalBranchQuota 1001)
     (let [i (k/var 0)]
       (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                     (< i 1001))))))

(comment
  (foo))
