(ns learn.example.test-inline-while
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- type-name-length :usize
  [[T {:zig/prefix "comptime"} :type]]
  (az/field (ak/typeName T) :len))

(az/deftest inline-while-test
  (let [index (ak/var 0 nil {:zig/prefix "comptime"})
        sum (ak/var 0 :usize)]
    (az/while-loop {:inline? true
                    :continue (az/assign-expr "+=" index 1)}
      (< index 3)
      (let [T (ak/switch index
                (case [0] :f32)
                (case [1] :i8)
                (case [2] :bool)
                (az/case-else (ak/unreachable)))]
        (ak/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-while-test))
