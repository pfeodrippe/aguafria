(ns learn.example.test-allowzero
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest allowzero-test
  (let [^:var address (ak/usize 0)]
    (set! _ (& address))
    (let [pointer (ak/as (ak/ptrFromInt address) [:pointer {:size :one, :allowzero? true} :i32])]
      (try (testing/expectEqual 0 (ak/intFromPtr pointer))))))

(comment
  (allowzero-test))
