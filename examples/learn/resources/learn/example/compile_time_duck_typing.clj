(ns learn.example.compile-time-duck-typing
  (:require [aguafria.zig :as az]))

(az/defn- maximum T
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (if (> left right) left right))

(az/defn- bigger-float :f32 [[left :f32] [right :f32]]
  (maximum :f32 left right))

(az/defn- bigger-integer :u64 [[left :u64] [right :u64]]
  (maximum :u64 left right))
