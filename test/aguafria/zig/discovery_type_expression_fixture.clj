(ns aguafria.zig.discovery-type-expression-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Point [[:x :i32] [:y :i32]])

(a/defn primitive-size :usize []
  (sizeOf (a/type :u32)))

(a/defn point-size :usize []
  (sizeOf (a/type Point)))

(a/defn array-size :usize []
  (sizeOf (a/type [:array 7 Point])))

(a/defn nested-type-size :usize []
  (let [n 5]
    (sizeOf (a/type [:array n :u16]))))
