(ns learn.example.test-reduce-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-reduce
  (let [V (az/type [:vector 4 :i32])
        value (az/init [1 -1 1 -1] V)
        result (k/> value (k/as (k/splat 0) V))]
    ;; result is { true, false, true, false };
    (try (k/comptime
          (testing/expectEqual (az/type [:vector 4 :bool])
                               (k/TypeOf result))))
    (let [is-all-true (k/reduce :.And result)]
      (try (k/comptime
            (testing/expectEqual (az/type :bool) (k/TypeOf is-all-true))))
      (try (testing/expectEqual false is-all-true)))))

(comment
  (vector-reduce))
