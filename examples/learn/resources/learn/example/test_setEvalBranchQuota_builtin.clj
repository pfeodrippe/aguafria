(ns learn.example.test-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest increased-branch-quota-test
  (az/comptime-stmt
    (let [^:var index 0]
      (ak/setEvalBranchQuota 1001)
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (< index 1001)))))

(comment
  (increased-branch-quota-test))
