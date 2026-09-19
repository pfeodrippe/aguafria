(ns learn.examples.idiomatic-pointers.null-terminated-slicing
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-slicing-test
  (let [^:var bytes (az/array-init [:array _ :u8] [3 2 1 0 3 2 1 0])
        ^{:var :usize} length 3]
    (set! _ (& length))
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (try (testing/expectEqual (az/type [:pointer {:size :slice :sentinel 0} :u8])
                                (ak/TypeOf slice)))
      (try (testing/expectEqual 3 (az/field slice :len))))))
