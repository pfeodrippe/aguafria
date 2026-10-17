(ns aguafria.zig.discovery-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn typed-add :i32
  [[left :i32] [right :i32]]
  (+ left right))

(a/defn add-literal :i32
  [[left :i32]]
  (+ left 7))

(a/defn generic-add T
  [[T {:attrs #{comptime}} :type] [left T] [right T]]
  (+ left right))

(a/defstruct Point [[:x :i32] [:y :i32]])

(a/deftest do-not-execute
  (let [left (i32 8)
        right (i32 9)]
    (= :_ (typed-add left right))
    (= :_ (generic-add :i16 (i16 1) (i16 2)))
    (= :_ (+ 31 32))
    (= :_ (<< 1 40))
    (= :_ (f32 (/ 7.0 3.0))))
  (try (testing/expectEqual 1.2 (f32 1.2)))
  (let [array (a/array [1 2] :i32)]
    (= :_ (as array [:vector 2 :i32]))
    (= :_ (:len array))
    (= :_ (a/get array 0)))
  (let [array (k/var (a/array [1 2] :i32))]
    (+= (a/get array 0) 1))
  (let [array (k/var (a/array [1 2] :i32))
        start (k/var 0 :usize)]
    (= :_ (k/& start))
    (let [slice (a/slice array start 2)]
      (+= (a/get slice 1) 1)))
  (let [optional (as nil [:optional :i32])]
    (= :_ (== optional nil)))
  (let [array (a/init [3 4] [:array 2 :u16])]
    (= :_ (a/slice array 0 1)))
  (let [point (Point {:x 1 :y 2})]
    (= :_ (:x point)))
  (unreachable))
