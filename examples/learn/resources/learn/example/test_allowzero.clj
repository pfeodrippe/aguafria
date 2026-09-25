(ns learn.example.test-allowzero
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest allowzero-test
  (let [address (k/var 0 :usize)]
    (k/= :_ (k/& address))
    (let [pointer (k/as (k/ptrFromInt address) [:pointer {:size :one, :allowzero? true} :i32])]
      (try (testing/expectEqual 0 (k/intFromPtr pointer))))))

(comment
  (allowzero-test))
