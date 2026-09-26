(ns learn.example.test-null-terminated-array
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest sentinel-array-test
  (let [bytes (az/init [1 2 3 4] [:array-sentinel :_ 0 :u8])]
    (try (testing/expectEqual (az/type [:array-sentinel 4 0 :u8])
                              (k/TypeOf bytes)))
    (try (testing/expectEqual 4 (:len bytes)))
    (try (testing/expectEqual 0 (az/get bytes 4)))))

(az/deftest embedded-zeroes-test
  ;; Embedded sentinel values do not change the array's compile-time length.
  (let [bytes (az/init [1 0 0 4] [:array-sentinel :_ 0 :u8])]
    (try (testing/expectEqual (az/type [:array-sentinel 4 0 :u8])
                              (k/TypeOf bytes)))
    (try (testing/expectEqual 4 (:len bytes)))
    (try (testing/expectEqual 0 (az/get bytes 4)))))

(comment
  (sentinel-array-test)
  (embedded-zeroes-test))
