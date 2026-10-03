(ns learn.example.test-misaligned-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct BitField {:layout :packed}
             [[:a :u3] [:b :u3] [:c :u2]])

(a/defvar bit-field (BitField {:a 1 :b 2 :c 3}))

(a/defn bar :u3 [[x [:*const :u3]]]
  @x)

(a/deftest pointer-to-non-byte-aligned-field
  (try (testing/expectEqual 2 (bar (k/& (:b bit-field))))))

(comment
  (pointer-to-non-byte-aligned-field))
