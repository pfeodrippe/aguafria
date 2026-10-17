(ns aguafria.zig.discovery-typed-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn take-index :usize [[index :usize]] index)

(a/defn take-float :f32 [[x :f32]] x)

(a/defn take-pointer [:*const :u8] [[pointer [:*const :u8]]] pointer)

(a/defn cast-index :usize [[index :u32]]
  (take-index (intCast index)))

(a/defn cast-float :f32 [[index :u32]]
  (take-float (floatFromInt index)))

(a/defn cast-pointer [:*const :u8] [[pointer [:*const [:array 2 :u8]]]]
  (take-pointer (ptrCast pointer)))
