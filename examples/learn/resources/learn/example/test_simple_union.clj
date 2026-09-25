(ns learn.example.test-simple-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Payload
  (az/union
    [[:int :i64]
     [:float :f64]
     [:boolean :bool]]))

(az/deftest simple-union-test
  (let [payload (ak/var (Payload {:int 1234}))]
    (try (testing/expectEqual 1234 (az/field payload :int)))
    ;; Assigning the whole union changes its active field.
    (ak/= payload (Payload {:float 12.34}))
    (try (testing/expectEqual 12.34 (az/field payload :float)))))

(comment
  (simple-union-test))
