(ns learn.example.test-integer-pointer-conversion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest intFromPtr-and-ptrFromInt
  (let [ptr (k/as (k/ptrFromInt 0xdeadbee0) [:* :i32])
        addr (k/intFromPtr ptr)]
    (try (testing/expectEqual (az/type :usize) (k/TypeOf addr)))
    (try (testing/expectEqual 0xdeadbee0 addr))))

(comment
  (intFromPtr-and-ptrFromInt))
