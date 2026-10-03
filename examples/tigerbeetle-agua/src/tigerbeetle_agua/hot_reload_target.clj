(ns tigerbeetle-agua.hot-reload-target
  "Small live callers for stable-dispatch and comptime propagation checks."
  (:require [aguafria.zig :as a]
            [tigerbeetle-agua.hot-reload-leaf :as leaf]))

(a/defn leaf-caller :u32 [] (leaf/leaf-value))

(a/defn comptime-caller :u32 [] (leaf/comptime-scale 5))
