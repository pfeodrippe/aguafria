(ns learn.example.test-packed-struct-field-address
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct BitField {:layout :packed}
             [[:a :u3] [:b :u3] [:c :u2]])

(a/defvar bit-field (BitField {:a 1 :b 2 :c 3}))

(a/deftest pointers-of-sub-byte-aligned-fields-share-addresses
  (try (testing/expectEqual (k/intFromPtr (k/& (:a bit-field))) (k/intFromPtr (k/& (:b bit-field)))))
  (try (testing/expectEqual (k/intFromPtr (k/& (:a bit-field))) (k/intFromPtr (k/& (:c bit-field))))))

(comment
  (pointers-of-sub-byte-aligned-fields-share-addresses))
