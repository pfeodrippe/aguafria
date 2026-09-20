(ns learn.example.test-aligned-struct-fields
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest aligned-struct-fields-test
  (let [AlignedFields (az/container {:kind :struct}
                        (az/field-decl :a {:zig/align 2} :u32)
                        (az/field-decl :b {:zig/align 64} :u32))
        ^:var fields (az/init AlignedFields {:a 1 :b 2})]
    ;; The strongest field alignment determines the containing struct's alignment.
    (try (testing/expectEqual 64 (ak/alignOf AlignedFields)))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 2} :u32])
                              (ak/TypeOf (& (az/field fields :a)))))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 64} :u32])
                              (ak/TypeOf (& (az/field fields :b)))))))

(comment
  (aligned-struct-fields-test))
