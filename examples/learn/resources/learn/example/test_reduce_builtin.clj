(ns learn.example.test-reduce-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest vector-reduce
  (let [V (a/type [:vector 4 :i32])
        value (a/init [1 -1 1 -1] V)
        result (> value (as (splat 0) V))]
    ;; result is { true, false, true, false };
    (k/try (comptime
            (testing/expectEqual (a/type [:vector 4 :bool])
                                 (TypeOf result))))
    (let [is-all-true (reduce :And result)]
      (k/try (comptime
              (testing/expectEqual (a/type :bool) (TypeOf is-all-true))))
      (k/try (testing/expectEqual false is-all-true)))))

(comment
  (vector-reduce))
