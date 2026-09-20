(ns learn.example.test-wrong-union-access
  (:require [aguafria.zig :as az]))

(az/defconst Payload
  (az/union
    [[:int :i64]
     [:float :f64]
     [:boolean :bool]]))

(az/deftest simple-union-test
  (let [^:var payload (az/init Payload {:int 1234})]
    ;; Intentional error: changing a field does not change the active member.
    (set! (az/field payload :float) 12.34)))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (simple-union-test))
