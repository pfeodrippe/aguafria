(ns learn.example.runtime-reaching-unreachable
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (debug/assert false))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
