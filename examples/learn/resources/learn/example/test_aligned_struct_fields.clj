(ns learn.example.test-aligned-struct-fields
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest aligned-struct-fields
  (let [S (az/struct
           [[:a {:zig/align 2} :u32]
            [:b {:zig/align 64} :u32]])
        foo (k/var (S {:a 1 :b 2}))]
    (try (testing/expectEqual 64 (k/alignOf S)))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 2} :u32])
                              (k/TypeOf (k/& (:a foo)))))
    (try (testing/expectEqual (az/type [:pointer {:size :one :align 64} :u32])
                              (k/TypeOf (k/& (:b foo)))))))

(comment
  (aligned-struct-fields))
