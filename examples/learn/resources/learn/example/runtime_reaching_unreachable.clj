(ns learn.example.runtime-reaching-unreachable
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  ;; The standard assertion reaches unreachable when its condition is false.
  (debug/assert false))
