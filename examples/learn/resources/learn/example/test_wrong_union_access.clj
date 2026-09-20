(ns learn.example.test-wrong-union-access
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Payload
  (az/union
    [[:int :i64]
     [:float :f64]
     [:boolean :bool]]))

(az/deftest simple-union-test
  (let [payload (ak/var (az/init {:int 1234} Payload))]
    ;; Intentional error: changing a field does not change the active member.
    (ak/= (az/field payload :float) 12.34)))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (simple-union-test))
