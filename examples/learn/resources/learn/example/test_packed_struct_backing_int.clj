(ns learn.example.test-packed-struct-backing-int
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct PackedStruct {:layout :packed :type :u8}
             [[:lo :u4]
              [:hi :u4]])

(a/deftest convert-to-and-from-backing-integer
  (let [original (PackedStruct {:lo (a/number-literal "0b1100")
                                :hi (a/number-literal "0b0101")})
        backing-int (k/backingInt original)]
    (k/comptime (debug/assert (k/== (k/TypeOf backing-int) :u8)))
    (try (testing/expectEqual (a/number-literal "0b0101_1100") backing-int))
    (let [reconstructed (k/as (k/fromBackingInt backing-int) PackedStruct)]
      (try (testing/expectEqual original reconstructed)))))

(comment
  (convert-to-and-from-backing-integer))
