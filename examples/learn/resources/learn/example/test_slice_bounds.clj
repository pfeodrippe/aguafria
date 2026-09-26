(ns learn.example.test-slice-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-slicing-test
  (let [numbers (k/var (az/array [1 2 3 4 5 6 7 8 9 10] :u8))
        start (k/var 2 :usize)]
    (k/= :_ (k/& start))
    (let [slice (az/slice numbers start 4)]
      (try (testing/expectEqual 2 (:len slice)))
      (try (testing/expectEqual 4 (az/get numbers 3)))
      ;; A slice shares the original array's storage.
      (k/+= (az/get slice 1) 1)
      (try (testing/expectEqual 5 (az/get numbers 3))))))

(comment
  (pointer-slicing-test))
