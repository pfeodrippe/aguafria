(ns aguafria.zig.discovery-specialization-caller-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.discovery-specialization-leaf-fixture :as leaf]))

(a/defn call-scale :u32 [] (leaf/scale 5))

(a/defn second-scale :u32 [] (leaf/scale 7))

(a/defn runtime-caller :u32
  [[value :u32]]
  (k/= :_ value)
  (leaf/scale 9))

(a/defn array-size :usize [] (leaf/element-size [:array 3 :u8]))
