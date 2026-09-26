(ns learn.example.test-null-terminated-slicing
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest zero-terminated-slicing
  (let [array (k/var (az/array [3 2 1 0 3 2 1 0] :u8))
        runtime-length (k/var 3 :usize)]
    (k/= :_ (k/& runtime-length))
    (let [slice (az/slice-sentinel array 0 runtime-length 0)]
      (try (testing/expectEqual (az/type [:pointer {:size :slice :sentinel 0} :u8])
                                (k/TypeOf slice)))
      (try (testing/expectEqual 3 (:len slice))))))

(comment
  (zero-terminated-slicing))
