(ns learn.example.test-tagged-union-with-tag-values
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Tagged
  (az/union {:argument :u32, :attrs #{k/enum}}
            [[:int {:default 123} :i64]
             [:boolean {:default 67} :bool]]))

(az/deftest tag-values
  (let [int (Tagged {:int -40})
        boolean (Tagged {:boolean false})]
    (try (testing/expectEqual 123 (k/intFromEnum int)))
    (try (testing/expectEqual 67 (k/intFromEnum boolean)))))

(comment
  (tag-values))
