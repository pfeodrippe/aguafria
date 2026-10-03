(ns learn.example.test-packed-structs
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

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
    (try (testing/expectEqual 0x34 (:half1 divided)))
    (try (testing/expectEqual 0x2 (:quarter3 divided)))
    (try (testing/expectEqual 0x1 (:quarter4 divided)))
    (try (testing/expectEqual 0x34 (az/get ordered 0)))
    (try (testing/expectEqual 0x12 (az/get ordered 1)))))

(az/deftest bitCast-between-packed-structs
  (try (doTheTest))
  (try (k/comptime (doTheTest))))

(comment
  (bitCast-between-packed-structs))
