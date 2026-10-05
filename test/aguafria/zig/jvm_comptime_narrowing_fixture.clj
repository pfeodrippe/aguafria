(ns aguafria.zig.jvm-comptime-narrowing-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn narrow-known-integer :u8 []
  (let [x (k/u64 255)]
    (k/u8 x)))

(a/defn echo-integer :u64 [[x :u64]]
  x)

(a/defn narrow-twice :u8 []
  (let [x (k/u64 255)
        y (k/u16 x)]
    (k/u8 y)))

(a/defn widen-integer :u64 [[x :u32]]
  (k/u64 x))
