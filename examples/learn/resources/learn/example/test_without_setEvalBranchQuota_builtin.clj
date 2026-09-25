(ns learn.example.test-without-setEvalBranchQuota-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest default-branch-quota-test
  (az/comptime-stmt
    (let [index (k/var 0)]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (k/< index 1001)))))

(comment
  (default-branch-quota-test))
