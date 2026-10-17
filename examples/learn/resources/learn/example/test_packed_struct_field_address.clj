(ns learn.example.test-packed-struct-field-address
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct BitField {:layout :packed}
             [[:a :u3] [:b :u3] [:c :u2]])

(a/defvar bit-field (BitField {:a 1 :b 2 :c 3}))

(a/deftest pointers-of-sub-byte-aligned-fields-share-addresses
  (k/try (testing/expectEqual (intFromPtr (k/& (:a bit-field))) (intFromPtr (k/& (:b bit-field)))))
  (k/try (testing/expectEqual (intFromPtr (k/& (:a bit-field))) (intFromPtr (k/& (:c bit-field))))))

(comment
  (pointers-of-sub-byte-aligned-fields-share-addresses))
