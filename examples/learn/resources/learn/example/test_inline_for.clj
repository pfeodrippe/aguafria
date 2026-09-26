(ns learn.example.test-inline-for
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- type-name-length :usize
  [[T {:attrs #{k/comptime}} :type]]
  (:len (k/typeName T)))

(az/deftest inline-for-test
  (let [numbers (az/array [2 4 6] :i32)
        sum (k/var 0 :usize)]
    (az/inline-for [number numbers]
      (let [T (k/switch number
                (case [2] :f32)
                (case [4] :i8)
                (case [6] :bool)
                (az/case-else (k/unreachable)))]
        (k/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-for-test))
