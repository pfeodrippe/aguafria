(ns learn.examples.idiomatic-pointers.null-terminated-slice
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-slice-test
  (let [^{:zig/type [:pointer {:size :slice :const? true :sentinel 0} :u8]}
        slice "hello"]
    (try (testing/expectEqual 5 (az/field slice :len)))
    ;; A sentinel slice permits reading its terminator at index len.
    (try (testing/expectEqual 0 (az/index slice 5)))))
