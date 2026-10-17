(ns learn.example.test-null-terminated-slice
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest zero-terminated-slice
  (let [slice (as "hello" [:* {:sentinel 0, :size :slice, :const? true} :u8])]
    (k/try (testing/expectEqual 5 (:len slice)))
    (k/try (testing/expectEqual 0 (a/get slice 5)))))

(comment
  (zero-terminated-slice))
