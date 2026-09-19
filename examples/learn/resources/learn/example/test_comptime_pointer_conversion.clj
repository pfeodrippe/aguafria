(ns learn.example.test-comptime-pointer-conversion
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointer-conversion-test
  (az/comptime-stmt
    ;; Integer-to-pointer conversion works at comptime if we never dereference it.
    (let [^{:zig/type [:* :i32]} pointer (ak/ptrFromInt 0xdeadbee0)
          address (ak/intFromPtr pointer)]
      (try (testing/expectEqual (az/type :usize) (ak/TypeOf address)))
      (try (testing/expectEqual 0xdeadbee0 address)))))
