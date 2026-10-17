(ns learn.example.test-packed-struct-backing-int
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct PackedStruct {:layout :packed :type :u8}
             [[:lo :u4]
              [:hi :u4]])

(a/deftest convert-to-and-from-backing-integer
  (let [original (PackedStruct {:lo (a/number-literal "0b1100")
                                :hi (a/number-literal "0b0101")})
        backing-int (backingInt original)]
    (comptime (debug/assert (== (TypeOf backing-int) :u8)))
    (k/try (testing/expectEqual (a/number-literal "0b0101_1100") backing-int))
    (let [reconstructed (as (fromBackingInt backing-int) PackedStruct)]
      (k/try (testing/expectEqual original reconstructed)))))

(comment
  (convert-to-and-from-backing-integer))
