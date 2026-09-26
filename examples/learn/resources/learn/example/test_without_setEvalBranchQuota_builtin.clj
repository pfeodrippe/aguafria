(ns learn.example.test-without-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest foo
  (az/comptime-stmt
   (let [i (k/var 0)]
     (az/while-loop {:continue (az/assign-expr "+=" i 1)}
                    (k/< i 1001)))))

(comment
  (foo))
