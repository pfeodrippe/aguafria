(ns aguafria.zig.discovery-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn typed-add :i32
  [[left :i32] [right :i32]]
  (k/+ left right))

(a/defn add-literal :i32
  [[left :i32]]
  (k/+ left 7))

(a/defn generic-add T
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (k/+ left right))

(a/defstruct Point [[:x :i32] [:y :i32]])

(a/deftest do-not-execute
  (let [left (k/i32 8)
        right (k/i32 9)]
    (k/= :_ (typed-add left right))
    (k/= :_ (generic-add :i16 (k/i16 1) (k/i16 2)))
    (k/= :_ (k/+ 31 32))
    (k/= :_ (k/<< 1 40))
    (k/= :_ (k/f32 (k// 7.0 3.0))))
  (try (testing/expectEqual 1.2 (k/f32 1.2)))
  (let [array (a/array [1 2] :i32)]
    (k/= :_ (k/as array [:vector 2 :i32]))
    (k/= :_ (:len array))
    (k/= :_ (a/get array 0)))
  (let [array (k/var (a/array [1 2] :i32))]
    (k/+= (a/get array 0) 1))
  (let [array (k/var (a/array [1 2] :i32))
        start (k/var 0 :usize)]
    (k/= :_ (k/& start))
    (let [slice (a/slice array start 2)]
      (k/+= (a/get slice 1) 1)))
  (let [optional (k/as nil [:optional :i32])]
    (k/= :_ (k/== optional nil)))
  (let [array (a/init [3 4] [:array 2 :u16])]
    (k/= :_ (a/slice array 0 1)))
  (let [point (Point {:x 1 :y 2})]
    (k/= :_ (:x point)))
  (k/unreachable))
