(ns learn.example.compile-time-duck-typing
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- max T
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (if (k/> a b) a b))

(az/defn- gimmeTheBiggerFloat :f32 [[a :f32] [b :f32]]
  (max :f32 a b))

(az/defn- gimmeTheBiggerInteger :u64 [[a :u64] [b :u64]]
  (max :u64 a b))

(comment
  (gimmeTheBiggerFloat 1.5 2.5)
  (gimmeTheBiggerInteger 12 34))
