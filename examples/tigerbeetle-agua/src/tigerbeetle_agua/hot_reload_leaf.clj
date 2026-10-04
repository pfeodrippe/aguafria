(ns tigerbeetle-agua.hot-reload-leaf
  "Small native leaves used to measure the generated TigerBeetle graph."
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn leaf-value :u32 [] 10)

(a/defn comptime-scale :u32
  [[value {:attrs #{k/comptime}} :u32]]
  (k/* value 2))
