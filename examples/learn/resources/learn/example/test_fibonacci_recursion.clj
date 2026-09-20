(ns learn.example.test-fibonacci-recursion
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- fibonacci :u32
  [[index :u32]]
  (if (< index 2)
    (ak/return index))
  (ak/return
   (+ (fibonacci (- index 1))
      (fibonacci (- index 2)))))

(az/deftest fibonacci-test
  ;; test fibonacci at run-time
  (try (testing/expectEqual 13 (fibonacci 7)))

  ;; test fibonacci at compile-time
  (try (ak/comptime (testing/expectEqual 13 (fibonacci 7)))))

(comment
  (fibonacci-test))
