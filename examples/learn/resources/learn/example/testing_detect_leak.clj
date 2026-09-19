(ns learn.example.testing-detect-leak
  (:require [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest detect-leak-test
  (let [allocator testing/allocator
        ^{:var (std/ArrayList :u21)} list :.empty]
    ;; Intentionally missing (defer ((az/field list :deinit) allocator)).
    (try ((az/field list :append) allocator \☔))
    (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))
