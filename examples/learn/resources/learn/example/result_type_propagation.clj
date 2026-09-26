(ns learn.example.result-type-propagation
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest struct-initializer-result-type-test
  (let [S (az/struct
            [[:x :u32]])
        value (k/u64 123)
        result (S {:x (k/intCast value)})]
    ;; The constructor supplies S as the initializer's result type.
    ;; intCast's result type is u32 because that is the type of S.x.
    ;; value has no result type: the cast accepts any integer type.
    (try (testing/expectEqual (k/as 123 :u32) (:x result)))))

(comment
  (struct-initializer-result-type-test))
