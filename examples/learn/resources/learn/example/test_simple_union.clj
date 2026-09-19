(ns learn.example.test-simple-union
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Payload
  (az/container {:kind :union}
    (az/field-decl :int :i64)
    (az/field-decl :float :f64)
    (az/field-decl :boolean :bool)))

(az/deftest simple-union-test
  (let [^:var payload (az/init Payload {:int 1234})]
    (try (testing/expectEqual 1234 (az/field payload :int)))
    ;; Assigning the whole union changes its active field.
    (set! payload (az/init Payload {:float 12.34}))
    (try (testing/expectEqual 12.34 (az/field payload :float)))))
