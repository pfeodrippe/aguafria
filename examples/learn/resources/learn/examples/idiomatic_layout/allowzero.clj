(ns learn.examples.idiomatic-layout.allowzero
  "Converted from test_allowzero.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest allowzero-test
  (let [^{:var :usize} address 0]
    (set! _ (& address))
    (let [^{:zig/type [:pointer {:size :one :allowzero? true} :i32]}
          pointer (ak/ptrFromInt address)]
      (try (testing/expectEqual 0 (ak/intFromPtr pointer))))))
