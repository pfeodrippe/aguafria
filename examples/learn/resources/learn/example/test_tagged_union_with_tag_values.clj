(ns learn.example.test-tagged-union-with-tag-values
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Tagged
  (az/union {:argument :u32, :attrs #{:enum}}
    [[:int {:default 123} :i64]
     [:boolean {:default 67} :bool]]))

(az/deftest explicit-tag-values-test
  (let [^{:zig/type Tagged} integer {:int -40}
        ^{:zig/type Tagged} boolean {:boolean false}]
    ;; The tag's integer value is independent of the payload's value.
    (try (testing/expectEqual 123 (ak/intFromEnum integer)))
    (try (testing/expectEqual 67 (ak/intFromEnum boolean)))))

(comment
  (explicit-tag-values-test))
