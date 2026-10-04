(ns aguafria.zig.discovery-specialization-leaf-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn scale :u32
  [[value {:attrs #{k/comptime}} :u32]]
  (k/* value 2))

(a/defn no-call-site :u32
  [[value {:attrs #{k/comptime}} :u32]]
  (k/+ value 3))

(a/defn element-size :usize
  [[T {:attrs #{k/comptime}} :type]]
  (k/sizeOf T))
