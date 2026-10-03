(ns learn.example.test-tagged-union-with-tag-values
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defunion Tagged {:type :u32, :attrs #{k/enum}}
  [[:int {:default 123} :i64]
   [:boolean {:default 67} :bool]])

(az/deftest tag-values
  (let [int (Tagged {:int -40})
        boolean (Tagged {:boolean false})]
    (try (testing/expectEqual 123 (k/backingInt int)))
    (try (testing/expectEqual 67 (k/backingInt boolean)))))

(comment
  (tag-values))
