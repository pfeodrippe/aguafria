(ns learn.example.test-reduce-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest vector-reduce
  (let [V (a/type [:vector 4 :i32])
        value (a/init [1 -1 1 -1] V)
        result (k/> value (k/as (k/splat 0) V))]
    ;; result is { true, false, true, false };
    (try (k/comptime
          (testing/expectEqual (a/type [:vector 4 :bool])
                               (k/TypeOf result))))
    (let [is-all-true (k/reduce :.And result)]
      (try (k/comptime
            (testing/expectEqual (a/type :bool) (k/TypeOf is-all-true))))
      (try (testing/expectEqual false is-all-true)))))

(comment
  (vector-reduce))
