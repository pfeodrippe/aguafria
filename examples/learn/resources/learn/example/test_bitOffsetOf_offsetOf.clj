(ns learn.example.test-bitOffsetOf-offsetOf
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct BitField {:layout :packed}
             [[:a :u3] [:b :u3] [:c :u2]])

(a/deftest offsets-of-non-byte-aligned-fields
  (k/comptime
   (do
     (try (testing/expectEqual 0 (k/bitOffsetOf BitField "a")))
     (try (testing/expectEqual 3 (k/bitOffsetOf BitField "b")))
     (try (testing/expectEqual 6 (k/bitOffsetOf BitField "c")))
     (try (testing/expectEqual 0 (k/offsetOf BitField "a")))
     (try (testing/expectEqual 0 (k/offsetOf BitField "b")))
     (try (testing/expectEqual 0 (k/offsetOf BitField "c"))))))

(comment
  (offsets-of-non-byte-aligned-fields))
