(ns learn.example.runtime-reaching-unreachable
  (:refer-clojure :only [])
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn main :void []
  (debug/assert false))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
