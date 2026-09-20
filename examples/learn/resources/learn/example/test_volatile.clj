(ns learn.example.test-volatile
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest volatile-test
  ;; Describe an MMIO address without actually accessing hardware in this test.
  (let [register (ak/as (ak/ptrFromInt 0x12345678) [:pointer {:volatile? true, :size :one} :u8])]
    (try (testing/expectEqual (az/type [:pointer {:size :one :volatile? true} :u8])
                              (ak/TypeOf register)))))

(comment
  (volatile-test))
