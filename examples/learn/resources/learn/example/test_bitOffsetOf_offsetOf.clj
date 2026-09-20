(ns learn.example.test-bitOffsetOf-offsetOf
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
  [[:a :u3] [:b :u3] [:c :u2]])

(az/deftest offsets-of-non-byte-aligned-fields-test
  (az/comptime-stmt
    (do
      (try (testing/expectEqual 0 (ak/bitOffsetOf BitField "a")))
      (try (testing/expectEqual 3 (ak/bitOffsetOf BitField "b")))
      (try (testing/expectEqual 6 (ak/bitOffsetOf BitField "c")))
      ;; All three fields occupy different bits of the same byte.
      (try (testing/expectEqual 0 (ak/offsetOf BitField "a")))
      (try (testing/expectEqual 0 (ak/offsetOf BitField "b")))
      (try (testing/expectEqual 0 (ak/offsetOf BitField "c"))))))

(comment
  (offsets-of-non-byte-aligned-fields-test))
