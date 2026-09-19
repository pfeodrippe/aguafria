(ns learn.example.test-packed-structs
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-endian
  (let [architecture (-> (ak/import "builtin")
                         (az/field :target)
                         (az/field :cpu)
                         (az/field :arch))]
    ((az/field architecture :endian))))

(az/defstruct Full {:layout :packed}
  [[:number :u16]])

(az/defstruct Divided {:layout :packed}
  [[:half1 :u8] [:quarter3 :u4] [:quarter4 :u4]])

(az/defn check-packed-bits [:error-union :void] []
  (try (testing/expectEqual 2 (ak/sizeOf Full)))
  (try (testing/expectEqual 2 (ak/sizeOf Divided)))
  (let [full (Full {:number 0x1234})
        ^{:zig/type Divided} divided (ak/bitCast full)
        ^{:zig/type [:array 2 :u8]} ordered (ak/bitCast full)]
    ;; Packed fields follow bit positions; array elements follow native byte order.
    (try (testing/expectEqual 0x34 (az/field divided :half1)))
    (try (testing/expectEqual 0x2 (az/field divided :quarter3)))
    (try (testing/expectEqual 0x1 (az/field divided :quarter4)))
    (az/switch-stmt native-endian
      (case [:.big]
        (do
          (try (testing/expectEqual 0x12 (az/index ordered 0)))
          (try (testing/expectEqual 0x34 (az/index ordered 1)))))
      (case [:.little]
        (do
          (try (testing/expectEqual 0x34 (az/index ordered 0)))
          (try (testing/expectEqual 0x12 (az/index ordered 1))))))))

(az/deftest bit-cast-between-packed-structs-test
  (try (check-packed-bits))
  (try (ak/comptime (check-packed-bits))))
