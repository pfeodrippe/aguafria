(ns learn.example.result-type-propagation
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest struct-initializer-result-type-test
  (let [S (az/struct
            [[:x :u32]])
        ^{:zig/type :u64} value 123
        result (S {:x (ak/intCast value)})]
    ;; The constructor supplies S as the initializer's result type.
    ;; intCast's result type is u32 because that is the type of S.x.
    ;; value has no result type: the cast accepts any integer type.
    (try (testing/expectEqual (ak/as :u32 123) (az/field result :x)))))

(comment
  (struct-initializer-result-type-test))
