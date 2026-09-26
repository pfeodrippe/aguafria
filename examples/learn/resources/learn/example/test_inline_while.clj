(ns learn.example.test-inline-while
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- type-name-length :usize
  [[T {:attrs #{k/comptime}} :type]]
  (:len (k/typeName T)))

(az/deftest inline-while-test
  (let [index (k/var 0 nil {:attrs #{k/comptime}})
        sum (k/var 0 :usize)]
    (az/while-loop {:inline? true
                    :continue (az/assign-expr "+=" index 1)}
      (k/< index 3)
      (let [T (k/switch index
                (case [0] :f32)
                (case [1] :i8)
                (case [2] :bool)
                (az/case-else (k/unreachable)))]
        (k/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-while-test))
