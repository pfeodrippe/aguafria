(ns learn.example.test-inline-for
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- type-name-length :usize
  [[T {:attrs #{comptime}} :type]]
  (:len (typeName T)))

(a/deftest inline-for-loop
  (let [nums (a/array [2 4 6] :i32)
        sum (k/var 0 :usize)]
    (a/inline-for [i nums]
                  (let [T (switch i
                            2 :f32
                            4 :i8
                            6 :bool
                            (unreachable))]
                    (+= sum (type-name-length T))))
    (k/try (testing/expectEqual 9 sum))))

(comment
  (inline-for-loop))
