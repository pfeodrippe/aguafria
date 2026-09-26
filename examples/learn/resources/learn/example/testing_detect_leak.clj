(ns learn.example.testing-detect-leak
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]
            [aguafria.std.ArrayList :as al]
            [aguafria.std.ArrayList.Slice :as al-slice]))

(az/deftest detect-leak
  (let [gpa testing/allocator
        list (k/var :.empty (std/ArrayList :u21))]
    ;; missing `defer list.deinit(gpa);`
    (try (al/append list gpa \☔))
    (try (testing/expectEqual 1 (-> list al/-items al-slice/-len)))))

(comment
  (detect-leak))
