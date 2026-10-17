(ns learn.example.test-comptime-pointer-conversion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest comptime-ptrFromInt
  (comptime
    ;; Zig is able to do this at compile-time, as long as
    ;; ptr is never dereferenced.
   (let [ptr (as (ptrFromInt 0xdeadbee0) [:* :i32])
         addr (intFromPtr ptr)]
     (k/try (testing/expectEqual (a/type :usize) (TypeOf addr)))
     (k/try (testing/expectEqual 0xdeadbee0 addr)))))

(comment
  (comptime-ptrFromInt))
