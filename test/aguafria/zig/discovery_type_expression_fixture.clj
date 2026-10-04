(ns aguafria.zig.discovery-type-expression-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Point [[:x :i32] [:y :i32]])

(a/defn primitive-size :usize []
  (k/sizeOf (a/type :u32)))

(a/defn point-size :usize []
  (k/sizeOf (a/type Point)))

(a/defn array-size :usize []
  (k/sizeOf (a/type [:array 7 Point])))

(a/defn nested-type-size :usize []
  (let [n 5]
    (k/sizeOf (a/type [:array n :u16]))))
