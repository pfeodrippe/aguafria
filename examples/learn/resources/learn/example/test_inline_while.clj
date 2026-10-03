(ns learn.example.test-inline-while
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- type-name-length :usize
  [[T {:attrs #{k/comptime}} :type]]
  (:len (k/typeName T)))

(a/deftest inline-while-loop
  (let [i (k/var 0 nil {:attrs #{k/comptime}})
        sum (k/var 0 :usize)]
    (a/while-loop {:inline? true
                   :continue (a/assign-expr "+=" i 1)}
                  (k/< i 3)
                  (let [T (k/switch i
                                    (case [0] :f32)
                                    (case [1] :i8)
                                    (case [2] :bool)
                                    (a/case-else (k/unreachable)))]
                    (k/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(comment
  (inline-while-loop))
