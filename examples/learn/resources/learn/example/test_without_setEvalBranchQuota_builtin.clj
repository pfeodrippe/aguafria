(ns learn.example.test-without-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest default-branch-quota-test
  (az/comptime-stmt
    (let [index (ak/var 0)]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (< index 1001)))))

(comment
  (default-branch-quota-test))
