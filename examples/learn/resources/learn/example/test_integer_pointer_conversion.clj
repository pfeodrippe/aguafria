(ns learn.example.test-integer-pointer-conversion
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-pointer-conversion-test
  (let [pointer (ak/as (ak/ptrFromInt 0xdeadbee0) [:* :i32])
        address (ak/intFromPtr pointer)]
    (try (testing/expectEqual (az/type :usize) (ak/TypeOf address)))
    (try (testing/expectEqual 0xdeadbee0 address))))

(comment
  (integer-pointer-conversion-test))
