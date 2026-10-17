(ns learn.example.result-type-propagation
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest result-type-propagates-through-struct-initializer
  (let [S (a/struct
           [[:x :u32]])
        val (u64 123)
        s (S {:x (intCast val)})]
    ;; .{ .x = @intCast(val) }   has result type `S` due to the type annotation
    ;;         @intCast(val)     has result type `u32` due to the type of the field `S.x`
    ;;                  val      has no result type, as it is permitted to be any integer type
    (k/try (testing/expectEqual (as 123 :u32) (:x s)))))

(comment
  (result-type-propagates-through-struct-initializer))
