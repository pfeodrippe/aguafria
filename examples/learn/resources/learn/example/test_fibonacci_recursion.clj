(ns learn.example.test-fibonacci-recursion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- fibonacci :u32
  [[index :u32]]
  (if (k/< index 2)
    (k/return index))
  (k/+ (fibonacci (k/- index 1))
       (fibonacci (k/- index 2))))

(az/deftest fibonacci-test
  ;; test fibonacci at run-time
  (try (testing/expectEqual 13 (fibonacci 7)))

  ;; test fibonacci at compile-time
  (try (k/comptime (testing/expectEqual 13 (fibonacci 7)))))

(comment
  (fibonacci-test))
