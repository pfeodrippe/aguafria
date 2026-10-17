(ns learn.example.test-null-terminated-slicing
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest zero-terminated-slicing
  (let [array (k/var (a/array [3 2 1 0 3 2 1 0] :u8))
        runtime-length (k/var 3 :usize)]
    (= :_ (k/& runtime-length))
    (let [slice (a/slice-sentinel array 0 runtime-length 0)]
      (k/try (testing/expectEqual (a/type [:* {:size :slice :sentinel 0} :u8])
                                  (TypeOf slice)))
      (k/try (testing/expectEqual 3 (:len slice))))))

(comment
  (zero-terminated-slicing))
