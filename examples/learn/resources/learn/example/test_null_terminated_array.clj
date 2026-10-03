(ns learn.example.test-null-terminated-array
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest zero-terminated-sentinel-array
  (let [array (a/array [1 2 3 4] {:sentinel 0} :u8)]
    (try (testing/expectEqual (a/type [:array 4 {:sentinel 0} :u8])
                              (k/TypeOf array)))
    (try (testing/expectEqual 4 (:len array)))
    (try (testing/expectEqual 0 (a/get array 4)))))

(a/deftest extra-0s-in-0-terminated-sentinel-array
  ;; The sentinel value may appear earlier, but does not influence the compile-time 'len'.
  (let [array (a/array [1 0 0 4] {:sentinel 0} :u8)]
    (try (testing/expectEqual (a/type [:array 4 {:sentinel 0} :u8])
                              (k/TypeOf array)))
    (try (testing/expectEqual 4 (:len array)))
    (try (testing/expectEqual 0 (a/get array 4)))))

(comment
  (zero-terminated-sentinel-array)
  (extra-0s-in-0-terminated-sentinel-array))
