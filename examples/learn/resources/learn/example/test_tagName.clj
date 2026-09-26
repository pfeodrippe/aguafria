(ns learn.example.test-tagName
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Small2
  (az/union {:attrs #{k/enum}}
            [[:a :i32]
             [:b :bool]
             [:c :u8]]))

(az/deftest tagName
  (try (testing/expectEqualSlices
        (az/type :u8) "a" (k/tagName (:a Small2)))))

(comment
  (tagName))
