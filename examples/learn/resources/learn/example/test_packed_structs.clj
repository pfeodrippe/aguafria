(ns learn.example.test-packed-structs
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-endian
  (let [architecture (-> builtin/target
                         target/-cpu
                         cpu/-arch)]
    ((az/field architecture :endian))))

(az/defstruct Full {:layout :packed}
  [[:number :u16]])

(az/defstruct Divided {:layout :packed}
  [[:half1 :u8] [:quarter3 :u4] [:quarter4 :u4]])

(az/defn doTheTest [:error-union :void] []
  (try (testing/expectEqual 2 (k/sizeOf Full)))
  (try (testing/expectEqual 2 (k/sizeOf Divided)))
  (let [full (Full {:number 0x1234})
        divided (k/as (k/bitCast full) Divided)
        ordered (k/as (k/bitCast full) [:array 2 :u8])]
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
  (try (doTheTest))
  (try (k/comptime (doTheTest))))

(comment
  (bit-cast-between-packed-structs-test))
