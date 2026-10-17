(ns tigerbeetle-agua.hot-reload-target
  "Small live callers for stable-dispatch and comptime propagation checks."
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [tigerbeetle-agua.hot-reload-leaf :as leaf]
            [aguafria.keyword :as k :refer :all]))

(a/defn leaf-caller :u32 [] (leaf/leaf-value))

(a/defn comptime-caller :u32 [] (leaf/comptime-scale 5))
