(ns learn.examples.idiomatic-errors.runtime-reaching-unreachable
  "Converted from runtime_reaching_unreachable.zig"
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  ;; The standard assertion reaches unreachable when its condition is false.
  (debug/assert false))
