(ns aguafria.zig.discovery-typed-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn take-index :usize [[index :usize]] index)

(a/defn take-float :f32 [[x :f32]] x)

(a/defn take-pointer [:*const :u8] [[pointer [:*const :u8]]] pointer)

(a/defn cast-index :usize [[index :u32]]
  (take-index (k/intCast index)))

(a/defn cast-float :f32 [[index :u32]]
  (take-float (k/floatFromInt index)))

(a/defn cast-pointer [:*const :u8] [[pointer [:*const [:array 2 :u8]]]]
  (take-pointer (k/ptrCast pointer)))
