(ns learn.example.test-tagName
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Small2 {:attrs #{enum}}
  [[:a :i32]
   [:b :bool]
   [:c :u8]])

(a/deftest tagName
  (k/try (testing/expectEqualSlices
          :u8 "a" (k/tagName (:a Small2)))))

(comment
  (tagName))
