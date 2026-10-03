(ns learn.example.test-null-terminated-slice
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest zero-terminated-slice
  (let [slice (k/as "hello" [:* {:sentinel 0, :size :slice, :const? true} :u8])]
    (try (testing/expectEqual 5 (:len slice)))
    (try (testing/expectEqual 0 (a/get slice 5)))))

(comment
  (zero-terminated-slice))
