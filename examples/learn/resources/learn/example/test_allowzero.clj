(ns learn.example.test-allowzero
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest allowzero
  (let [zero (k/var 0 :usize)] ; var to make to runtime-known
    (k/= :_ (k/& zero)) ; suppress 'var is never mutated' error
    (let [ptr (k/as (k/ptrFromInt zero) [:pointer {:size :one, :allowzero? true} :i32])]
      (try (testing/expectEqual 0 (k/intFromPtr ptr))))))

(comment
  (allowzero))
