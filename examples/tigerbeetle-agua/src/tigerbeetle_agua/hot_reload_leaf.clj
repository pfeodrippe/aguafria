(ns tigerbeetle-agua.hot-reload-leaf
  "Small native leaves used to measure the generated TigerBeetle graph."
  (:require [aguafria.zig :as a]))

(a/defn leaf-value :u32 [] 10)

(a/defn comptime-scale :u32
  [[value {:zig/prefix "comptime"} :u32]]
  (* value 2))
