(ns learn.example.test-comptime-pointers
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointers-test
  (az/comptime-stmt
    (let [^{:var :i32} value 1
          pointer (& value)]
      (ak/+= @pointer 1)
      (ak/+= value 1)
      (try (testing/expectEqual 3 @pointer)))))
