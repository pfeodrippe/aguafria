(ns learn.example.test-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest increased-branch-quota-test
  (az/comptime-stmt
    (let [index (k/var 0)]
      (k/setEvalBranchQuota 1001)
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (k/< index 1001)))))

(comment
  (increased-branch-quota-test))
