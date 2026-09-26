(ns learn.example.test-packed-struct-field-address
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
  [[:a :u3] [:b :u3] [:c :u2]])

(az/defvar bits (BitField {:a 1 :b 2 :c 3}))

(az/deftest pointers-of-sub-byte-aligned-fields-share-addresses-test
  (let [first-address (k/intFromPtr (k/& (:a bits)))]
    ;; The bit offset belongs to the pointer type, not to its integer address.
    (try (testing/expectEqual first-address (k/intFromPtr (k/& (:b bits)))))
    (try (testing/expectEqual first-address (k/intFromPtr (k/& (:c bits)))))))

(comment
  (pointers-of-sub-byte-aligned-fields-share-addresses-test))
