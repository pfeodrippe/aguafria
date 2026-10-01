(ns aguafria.zig.discovery-typed-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn take-index :usize [[index :usize]] index)

(az/defn take-float :f32 [[x :f32]] x)

(az/defn take-pointer [:*const :u8] [[pointer [:*const :u8]]] pointer)

(az/defn cast-index :usize [[index :u32]]
  (take-index (k/intCast index)))

(az/defn cast-float :f32 [[index :u32]]
  (take-float (k/floatFromInt index)))

(az/defn cast-pointer [:*const :u8] [[pointer [:*const [:array 2 :u8]]]]
  (take-pointer (k/ptrCast pointer)))
