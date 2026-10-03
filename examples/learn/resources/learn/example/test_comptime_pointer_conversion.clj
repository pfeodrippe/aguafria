(ns learn.example.test-comptime-pointer-conversion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest comptime-ptrFromInt
  (k/comptime
    ;; Zig is able to do this at compile-time, as long as
    ;; ptr is never dereferenced.
   (let [ptr (k/as (k/ptrFromInt 0xdeadbee0) [:* :i32])
         addr (k/intFromPtr ptr)]
     (try (testing/expectEqual (a/type :usize) (k/TypeOf addr)))
     (try (testing/expectEqual 0xdeadbee0 addr)))))

(comment
  (comptime-ptrFromInt))
