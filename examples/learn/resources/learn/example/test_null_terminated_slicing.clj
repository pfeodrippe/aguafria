(ns learn.example.test-null-terminated-slicing
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-slicing-test
  (let [bytes (k/var (az/array [3 2 1 0 3 2 1 0] :u8))
        length (k/var 3 :usize)]
    (k/= :_ (k/& length))
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (try (testing/expectEqual (az/type [:pointer {:size :slice :sentinel 0} :u8])
                                (k/TypeOf slice)))
      (try (testing/expectEqual 3 (:len slice))))))

(comment
  (sentinel-slicing-test))
