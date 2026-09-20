(ns learn.example.testing-detect-leak
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]
            [aguafria.std.ArrayList :as al]
            [aguafria.std.ArrayList.Slice :as al-slice]))

(az/deftest detect-leak-test
  (let [allocator testing/allocator
        list (ak/var :.empty (std/ArrayList :u21))]
    ;; Intentionally missing (ak/defer (al/deinit list allocator)).
    (try (al/append list allocator \☔))
    (try (testing/expectEqual 1 (-> list al/-items al-slice/-len)))))

(comment
  (detect-leak-test))
