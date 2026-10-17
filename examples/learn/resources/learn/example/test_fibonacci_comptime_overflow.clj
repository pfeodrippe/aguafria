(ns learn.example.test-fibonacci-comptime-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- fibonacci :u32 [[index :u32]]
  ;;if (index < 2) return index;
  (+ (fibonacci (- index 1))
     (fibonacci (- index 2))))

(a/deftest fibonacci-test
  (k/try (comptime (testing/expectEqual 13 (fibonacci 7)))))

(comment
  (fibonacci-test))
