(ns learn.example.test-reduce-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-reduction-test
  (let [Vector (az/type [:vector 4 :i32])
        values (az/init [1 -1 1 -1] Vector)
        positive (k/> values (k/as (k/splat 0) Vector))
        all-positive (k/reduce :.And positive)]
    (try (k/comptime
          (testing/expectEqual (az/type [:vector 4 :bool])
                               (k/TypeOf positive))))
    (try (k/comptime
          (testing/expectEqual (az/type :bool) (k/TypeOf all-positive))))
    (try (testing/expectEqual false all-positive))))

(comment
  (vector-reduction-test))
