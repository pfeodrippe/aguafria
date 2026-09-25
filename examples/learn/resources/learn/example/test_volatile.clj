(ns learn.example.test-volatile
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest volatile-test
  ;; Describe an MMIO address without actually accessing hardware in this test.
  (let [register (k/as (k/ptrFromInt 0x12345678) [:pointer {:volatile? true, :size :one} :u8])]
    (try (testing/expectEqual (az/type [:pointer {:size :one :volatile? true} :u8])
                              (k/TypeOf register)))))

(comment
  (volatile-test))
