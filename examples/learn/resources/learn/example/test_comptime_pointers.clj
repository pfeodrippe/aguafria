(ns learn.example.test-comptime-pointers
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest comptime-pointers
  (az/comptime-stmt
   (let [x (k/var 1 :i32)
         ptr (k/& x)]
     (k/+= @ptr 1)
     (k/+= x 1)
     (try (testing/expectEqual 3 @ptr)))))

(comment
  (comptime-pointers))
