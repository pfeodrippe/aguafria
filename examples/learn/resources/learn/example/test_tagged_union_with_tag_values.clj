(ns learn.example.test-tagged-union-with-tag-values
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Tagged {:type :u32, :attrs #{enum}}
  [[:int {:default 123} :i64]
   [:boolean {:default 67} :bool]])

(a/deftest tag-values
  (let [int (Tagged {:int -40})
        boolean (Tagged {:boolean false})]
    (k/try (testing/expectEqual 123 (backingInt int)))
    (k/try (testing/expectEqual 67 (backingInt boolean)))))

(comment
  (tag-values))
