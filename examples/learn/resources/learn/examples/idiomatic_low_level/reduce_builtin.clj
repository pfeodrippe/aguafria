(ns learn.examples.idiomatic-low-level.reduce-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-reduction-test
  (let [Vector (az/type [:vector 4 :i32])
        values (az/array-init Vector [1 -1 1 -1])
        positive (> values (ak/as Vector (ak/splat 0)))
        all-positive (ak/reduce :.And positive)]
    (try (ak/comptime
           (testing/expectEqual (az/type [:vector 4 :bool])
                                (ak/TypeOf positive))))
    (try (ak/comptime
           (testing/expectEqual (az/type :bool) (ak/TypeOf all-positive))))
    (try (testing/expectEqual false all-positive))))
