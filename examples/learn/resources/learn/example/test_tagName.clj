(ns learn.example.test-tagName
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Small2
  (az/union {:attrs #{:enum}}
    [[:a :i32]
     [:b :bool]
     [:c :u8]]))

(az/deftest tag-name-test
  (try (testing/expectEqualSlices
        (az/type :u8) "a" (ak/tagName (az/field Small2 :a)))))

(comment
  (tag-name-test))
