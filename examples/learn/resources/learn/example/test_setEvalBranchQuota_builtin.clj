(ns learn.example.test-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest foo
  (k/comptime
   (do
     (k/setEvalBranchQuota 1001)
     (let [i (k/var 0)]
       (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                     (k/< i 1001))))))

(comment
  (foo))
