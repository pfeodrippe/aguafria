(ns aguafria.zig.jvm-comptime-narrowing-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn narrow-known-integer :u8 []
  (let [x (u64 255)]
    (u8 x)))

(a/defn echo-integer :u64 [[x :u64]]
  x)

(a/defn narrow-twice :u8 []
  (let [x (u64 255)
        y (u16 x)]
    (u8 y)))

(a/defn widen-integer :u64 [[x :u32]]
  (u64 x))
