(ns learn.example.test-wrong-union-access
  (:require [aguafria.zig :as az]))

(az/defconst Payload
  (az/container {:kind :union}
    (az/field-decl :int :i64)
    (az/field-decl :float :f64)
    (az/field-decl :boolean :bool)))

(az/deftest simple-union-test
  (let [^:var payload (az/init Payload {:int 1234})]
    ;; Intentional error: changing a field does not change the active member.
    (set! (az/field payload :float) 12.34)))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (simple-union-test))
