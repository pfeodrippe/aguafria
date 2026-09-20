(ns learn.example.test-packed-struct-field-address
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
  [[:a :u3] [:b :u3] [:c :u2]])

(az/defvar bits (BitField {:a 1 :b 2 :c 3}))

(az/deftest pointers-of-sub-byte-aligned-fields-share-addresses-test
  (let [first-address (ak/intFromPtr (& (az/field bits :a)))]
    ;; The bit offset belongs to the pointer type, not to its integer address.
    (try (testing/expectEqual first-address (ak/intFromPtr (& (az/field bits :b)))))
    (try (testing/expectEqual first-address (ak/intFromPtr (& (az/field bits :c)))))))

(comment
  (pointers-of-sub-byte-aligned-fields-share-addresses-test))
