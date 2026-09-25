(ns learn.example.test-comptime-pointers
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointers-test
  (az/comptime-stmt
    (let [value (k/var 1 :i32)
          pointer (k/& value)]
      (k/+= @pointer 1)
      (k/+= value 1)
      (try (testing/expectEqual 3 @pointer)))))

(comment
  (comptime-pointers-test))
