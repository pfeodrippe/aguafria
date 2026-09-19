(ns learn.example.test-integer-pointer-conversion
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-pointer-conversion-test
  (let [^{:zig/type [:* :i32]} pointer (ak/ptrFromInt 0xdeadbee0)
        address (ak/intFromPtr pointer)]
    (try (testing/expectEqual (az/type :usize) (ak/TypeOf address)))
    (try (testing/expectEqual 0xdeadbee0 address))))
