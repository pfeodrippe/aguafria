(ns learn.example.test-misaligned-pointer
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
  [[:a :u3] [:b :u3] [:c :u2]])

(az/defvar bits (BitField {:a 1 :b 2 :c 3}))

(az/defn read-three-bits :u3 [[pointer [:*const :u3]]]
  @pointer)

(az/deftest pointer-to-non-byte-aligned-field-test
  ;; Intentionally rejected: a packed-field pointer carries bit-offset metadata
  ;; that an ordinary *const u3 parameter cannot represent.
  (try (testing/expectEqual 2 (read-three-bits (& (az/field bits :b))))))
