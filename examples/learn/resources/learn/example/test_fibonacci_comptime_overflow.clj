(ns learn.example.test-fibonacci-comptime-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- fibonacci :u32 [[index :u32]]
  ;;if (index < 2) return index;
  (k/+ (fibonacci (k/- index 1))
       (fibonacci (k/- index 2))))

(az/deftest fibonacci-test
  (try (k/comptime (testing/expectEqual 13 (fibonacci 7)))))

(comment
  (fibonacci-test))
