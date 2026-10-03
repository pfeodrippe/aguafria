(ns learn.example.test-without-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest foo
  (k/comptime
   (let [i (k/var 0)]
     (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                   (k/< i 1001)))))

(comment
  (foo))
