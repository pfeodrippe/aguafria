(ns learn.examples.idiomatic-layout.pointer-to-non-byte-aligned-field
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
  [[:a :u3] [:b :u3] [:c :u2]])

(az/defvar bits (BitField {:a 1 :b 2 :c 3}))

(az/deftest pointer-to-non-byte-aligned-field-test
  ;; Keeping the inferred pointer type retains the sub-byte offset.
  (let [pointer (& (az/field bits :b))]
    (try (testing/expectEqual 2 @pointer))))
