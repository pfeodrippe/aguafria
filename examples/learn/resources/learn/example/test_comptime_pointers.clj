(ns learn.example.test-comptime-pointers
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest comptime-pointers
  (comptime
   (let [x (k/var 1 :i32)
         ptr (k/& x)]
     (+= @ptr 1)
     (+= x 1)
     (k/try (testing/expectEqual 3 @ptr)))))

(comment
  (comptime-pointers))
