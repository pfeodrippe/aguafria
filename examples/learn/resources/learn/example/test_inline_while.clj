(ns learn.example.test-inline-while
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- type-name-length :usize
  [[T {:attrs #{comptime}} :type]]
  (:len (typeName T)))

(a/deftest inline-while-loop
  (let [i (k/var 0 nil {:attrs #{:comptime}})
        sum (k/var 0 :usize)]
    (a/while-loop {:inline? true
                   :continue (a/assign-expr "+=" i 1)}
                  (< i 3)
                  (let [T (switch i
                            0 :f32
                            1 :i8
                            2 :bool
                            (unreachable))]
                    (+= sum (type-name-length T))))
    (k/try (testing/expectEqual 9 sum))))

(comment
  (inline-while-loop))
