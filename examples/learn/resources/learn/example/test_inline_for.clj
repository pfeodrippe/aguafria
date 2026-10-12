(ns learn.example.test-inline-for
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- type-name-length :usize
  [[T {:attrs #{k/comptime}} :type]]
  (:len (k/typeName T)))

(a/deftest inline-for-loop
  (let [nums (a/array [2 4 6] :i32)
        sum (k/var 0 :usize)]
    (a/inline-for [i nums]
                  (let [T (k/switch i
                            2 :f32
                            4 :i8
                            6 :bool
                            (k/unreachable))]
                    (k/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-for-loop))
