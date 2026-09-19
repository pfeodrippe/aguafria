(ns learn.example.test-tagged-union-with-tag-values
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Tagged
  (az/container {:kind :union :attrs #{:enum} :argument :u32}
    (az/field-decl :int :i64 123)
    (az/field-decl :boolean :bool 67)))

(az/deftest explicit-tag-values-test
  (let [^{:zig/type Tagged} integer {:int -40}
        ^{:zig/type Tagged} boolean {:boolean false}]
    ;; The tag's integer value is independent of the payload's value.
    (try (testing/expectEqual 123 (ak/intFromEnum integer)))
    (try (testing/expectEqual 67 (ak/intFromEnum boolean)))))
