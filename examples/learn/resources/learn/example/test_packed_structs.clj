(ns learn.example.test-packed-structs
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Full {:layout :packed}
             [[:number :u16]])

(a/defstruct Divided {:layout :packed}
             [[:half1 :u8] [:quarter3 :u4] [:quarter4 :u4]])

(a/defn doTheTest [:error-union :void] []
  (k/try (testing/expectEqual 2 (sizeOf Full)))
  (k/try (testing/expectEqual 2 (sizeOf Divided)))
  (let [full (Full {:number 0x1234})
        divided (as (bitCast full) Divided)
        ordered (as (bitCast full) [:array 2 :u8])]
    (k/try (testing/expectEqual 0x34 (:half1 divided)))
    (k/try (testing/expectEqual 0x2 (:quarter3 divided)))
    (k/try (testing/expectEqual 0x1 (:quarter4 divided)))
    (k/try (testing/expectEqual 0x34 (a/get ordered 0)))
    (k/try (testing/expectEqual 0x12 (a/get ordered 1)))))

(a/deftest bitCast-between-packed-structs
  (k/try (doTheTest))
  (k/try (comptime (doTheTest))))

(comment
  (bitCast-between-packed-structs))
