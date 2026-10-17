(ns learn.example.test-bitOffsetOf-offsetOf
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct BitField {:layout :packed}
             [[:a :u3] [:b :u3] [:c :u2]])

(a/deftest offsets-of-non-byte-aligned-fields
  (comptime
   (do
     (k/try (testing/expectEqual 0 (bitOffsetOf BitField "a")))
     (k/try (testing/expectEqual 3 (bitOffsetOf BitField "b")))
     (k/try (testing/expectEqual 6 (bitOffsetOf BitField "c")))
     (k/try (testing/expectEqual 0 (offsetOf BitField "a")))
     (k/try (testing/expectEqual 0 (offsetOf BitField "b")))
     (k/try (testing/expectEqual 0 (offsetOf BitField "c"))))))

(comment
  (offsets-of-non-byte-aligned-fields))
