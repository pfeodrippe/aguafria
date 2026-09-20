(ns learn.example.test-comptime-pointers
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointers-test
  (az/comptime-stmt
    (let [value (ak/var 1 :i32)
          pointer (& value)]
      (ak/+= @pointer 1)
      (ak/+= value 1)
      (try (testing/expectEqual 3 @pointer)))))

(comment
  (comptime-pointers-test))
