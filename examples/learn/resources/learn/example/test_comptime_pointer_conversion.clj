(ns learn.example.test-comptime-pointer-conversion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointer-conversion-test
  (az/comptime-stmt
    ;; Integer-to-pointer conversion works at comptime if we never dereference it.
    (let [pointer (k/as (k/ptrFromInt 0xdeadbee0) [:* :i32])
          address (k/intFromPtr pointer)]
      (try (testing/expectEqual (az/type :usize) (k/TypeOf address)))
      (try (testing/expectEqual 0xdeadbee0 address)))))

(comment
  (comptime-pointer-conversion-test))
