(ns learn.example.test-integer-pointer-conversion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest intFromPtr-and-ptrFromInt
  (let [ptr (as (ptrFromInt 0xdeadbee0) [:* :i32])
        addr (intFromPtr ptr)]
    (k/try (testing/expectEqual (a/type :usize) (TypeOf addr)))
    (k/try (testing/expectEqual 0xdeadbee0 addr))))

(comment
  (intFromPtr-and-ptrFromInt))
