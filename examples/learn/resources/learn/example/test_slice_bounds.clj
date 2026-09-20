(ns learn.example.test-slice-bounds
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-slicing-test
  (let [numbers (ak/var (az/array-init [1 2 3 4 5 6 7 8 9 10] [:array :_ :u8]))
        start (ak/var 2 :usize)]
    (ak/= :_ (& start))
    (let [slice (az/slice numbers start 4)]
      (try (testing/expectEqual 2 (az/field slice :len)))
      (try (testing/expectEqual 4 (az/index numbers 3)))
      ;; A slice shares the original array's storage.
      (ak/+= (az/index slice 1) 1)
      (try (testing/expectEqual 5 (az/index numbers 3))))))

(comment
  (pointer-slicing-test))
