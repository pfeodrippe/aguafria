(ns learn.example.test-integer-pointer-conversion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-pointer-conversion-test
  (let [pointer (k/as (k/ptrFromInt 0xdeadbee0) [:* :i32])
        address (k/intFromPtr pointer)]
    (try (testing/expectEqual (az/type :usize) (k/TypeOf address)))
    (try (testing/expectEqual 0xdeadbee0 address))))

(comment
  (integer-pointer-conversion-test))
