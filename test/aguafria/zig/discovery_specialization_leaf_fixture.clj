(ns aguafria.zig.discovery-specialization-leaf-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn scale :u32
  [[value {:attrs #{comptime}} :u32]]
  (* value 2))

(a/defn no-call-site :u32
  [[value {:attrs #{comptime}} :u32]]
  (+ value 3))

(a/defn element-size :usize
  [[T {:attrs #{comptime}} :type]]
  (sizeOf T))
