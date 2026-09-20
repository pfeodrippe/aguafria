(ns learn.example.testing-detect-leak
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]
            [aguafria.std.ArrayList :as al]))

(az/deftest detect-leak-test
  (let [allocator testing/allocator
        list (ak/var :.empty (std/ArrayList :u21))]
    ;; Intentionally missing (defer ((az/field list :deinit) allocator)).
    (try (al/append list allocator \☔))
    (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))

(comment
  (detect-leak-test))
