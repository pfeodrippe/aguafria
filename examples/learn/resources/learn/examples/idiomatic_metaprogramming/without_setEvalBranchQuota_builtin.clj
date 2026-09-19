(ns learn.examples.idiomatic-metaprogramming.without-setEvalBranchQuota-builtin
  (:require [aguafria.zig :as az]))

(az/deftest default-branch-quota-test
  (az/comptime-stmt
    (let [^:var index 0]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (< index 1001)))))
