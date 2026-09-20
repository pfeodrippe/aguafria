(ns learn.example.test-null-terminated-array
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-array-test
  (let [bytes (az/array-init [:array-sentinel _ 0 :u8] [1 2 3 4])]
    (try (testing/expectEqual (az/type [:array-sentinel 4 0 :u8])
                              (ak/TypeOf bytes)))
    (try (testing/expectEqual 4 (az/field bytes :len)))
    (try (testing/expectEqual 0 (az/index bytes 4)))))

(az/deftest embedded-zeroes-test
  ;; Embedded sentinel values do not change the array's compile-time length.
  (let [bytes (az/array-init [:array-sentinel _ 0 :u8] [1 0 0 4])]
    (try (testing/expectEqual (az/type [:array-sentinel 4 0 :u8])
                              (ak/TypeOf bytes)))
    (try (testing/expectEqual 4 (az/field bytes :len)))
    (try (testing/expectEqual 0 (az/index bytes 4)))))

(comment
  (sentinel-array-test)
  (embedded-zeroes-test))
