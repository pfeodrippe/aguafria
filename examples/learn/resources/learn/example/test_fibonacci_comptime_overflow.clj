(ns learn.example.test-fibonacci-comptime-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- fibonacci :u32 [[index :u32]]
  (+ (fibonacci (- index 1))
     (fibonacci (- index 2))))

(az/deftest fibonacci-overflow-test
  (try (ak/comptime (testing/expectEqual 13 (fibonacci 7)))))
