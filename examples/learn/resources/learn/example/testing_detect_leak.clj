(ns learn.example.testing-detect-leak
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [aguafria.std.ArrayList :as al]
            [aguafria.std.ArrayList.Slice :as al-slice]))

(a/deftest detect-leak
  (let [gpa testing/allocator
        list (k/var :empty (std/ArrayList :u21))]
    ;; missing `defer list.deinit(gpa);`
    (k/try (al/append list gpa \☔))
    (k/try (testing/expectEqual 1 (-> list al/-items al-slice/-len)))))

(comment
  (detect-leak))
