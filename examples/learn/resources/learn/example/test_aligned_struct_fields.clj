(ns learn.example.test-aligned-struct-fields
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest aligned-struct-fields-test
  (let [AlignedFields (az/struct
                        [[:a {:zig/align 2} :u32]
                         [:b {:zig/align 64} :u32]])
        fields (k/var (AlignedFields {:a 1 :b 2}))]
    ;; The strongest field alignment determines the containing struct's alignment.
    (try (testing/expectEqual 64 (k/alignOf AlignedFields)))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 2} :u32])
                              (k/TypeOf (k/& (az/field fields :a)))))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 64} :u32])
                              (k/TypeOf (k/& (az/field fields :b)))))))

(comment
  (aligned-struct-fields-test))
