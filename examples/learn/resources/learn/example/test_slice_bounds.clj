(ns learn.example.test-slice-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-slicing
  (let [array (k/var (az/array [1 2 3 4 5 6 7 8 9 10] :u8))
        start (k/var 2 :usize)] ; var to make it runtime-known
    (k/= :_ (k/& start)) ; suppress 'var is never mutated' error
    (let [slice (az/slice array start 4)]
      (try (testing/expectEqual 2 (:len slice)))
      (try (testing/expectEqual 4 (az/get array 3)))
      (k/+= (az/get slice 1) 1)
      (try (testing/expectEqual 5 (az/get array 3))))))

(comment
  (pointer-slicing))
