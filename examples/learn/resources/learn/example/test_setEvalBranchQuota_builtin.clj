(ns learn.example.test-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest foo
  (az/comptime-stmt
   (do
     (k/setEvalBranchQuota 1001)
     (let [i (k/var 0)]
       (az/while-loop {:continue (az/assign-expr "+=" i 1)}
                      (k/< i 1001))))))

(comment
  (foo))
