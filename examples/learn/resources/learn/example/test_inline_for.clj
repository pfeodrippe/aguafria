(ns learn.example.test-inline-for
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- type-name-length :usize
  [[T {:attrs #{ak/comptime}} :type]]
  (az/field (ak/typeName T) :len))

(az/deftest inline-for-test
  (let [numbers (az/array-init [2 4 6] [:array :_ :i32])
        sum (ak/var 0 :usize)]
    (az/inline-for [number numbers]
      (let [T (ak/switch number
                (case [2] :f32)
                (case [4] :i8)
                (case [6] :bool)
                (az/case-else (ak/unreachable)))]
        (ak/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-for-test))
