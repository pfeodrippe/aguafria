(ns learn.example.test-null-terminated-slice
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-slice-test
  (let [slice (ak/as "hello" [:pointer {:sentinel 0, :size :slice, :const? true} :u8])]
    (try (testing/expectEqual 5 (az/field slice :len)))
    ;; A sentinel slice permits reading its terminator at index len.
    (try (testing/expectEqual 0 (az/index slice 5)))))

(comment
  (sentinel-slice-test))
