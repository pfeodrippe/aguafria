(ns learn.example.result-type-propagation
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest result-type-propagates-through-struct-initializer
  (let [S (az/struct
           [[:x :u32]])
        val (k/u64 123)
        s (S {:x (k/intCast val)})]
    ;; .{ .x = @intCast(val) }   has result type `S` due to the type annotation
    ;;         @intCast(val)     has result type `u32` due to the type of the field `S.x`
    ;;                  val      has no result type, as it is permitted to be any integer type
    (try (testing/expectEqual (k/as 123 :u32) (:x s)))))

(comment
  (result-type-propagates-through-struct-initializer))
