(ns learn.example.test-tagName
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Small2 {:attrs #{k/enum}}
  [[:a :i32]
   [:b :bool]
   [:c :u8]])

(a/deftest tagName
  (try (testing/expectEqualSlices
        :u8 "a" (k/tagName (:a Small2)))))

(comment
  (tagName))
