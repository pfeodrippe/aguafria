(ns tigerbeetle-agua.hot-reload-target
  "Small live callers for stable-dispatch and comptime propagation checks."
  (:require [aguafria.zig :as az]
            [tigerbeetle-agua.hot-reload-leaf :as leaf]))

(az/defn leaf-caller :- :u32 [] (leaf/leaf-value))

(az/defn comptime-caller :- :u32 [] (leaf/comptime-scale 5))
