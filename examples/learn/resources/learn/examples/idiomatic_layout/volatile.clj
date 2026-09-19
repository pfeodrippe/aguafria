(ns learn.examples.idiomatic-layout.volatile
  "Converted from test_volatile.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest volatile-test
  ;; Describe an MMIO address without actually accessing hardware in this test.
  (let [^{:zig/type [:pointer {:size :one :volatile? true} :u8]}
        register (ak/ptrFromInt 0x12345678)]
    (try (testing/expectEqual (az/type [:pointer {:size :one :volatile? true} :u8])
                              (ak/TypeOf register)))))
