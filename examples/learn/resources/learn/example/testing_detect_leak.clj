(ns learn.example.testing-detect-leak
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest detect-leak-test
  (let [allocator testing/allocator
        ^:var list (ak/as :.empty (std/ArrayList :u21))]
    ;; Intentionally missing (defer ((az/field list :deinit) allocator)).
    (try ((az/field list :append) allocator \☔))
    (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))

(comment
  (detect-leak-test))
