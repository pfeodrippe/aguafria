(ns learn.example.export-builtin-equivalent-code
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn foo :void
  {:attrs #{k/export}}
  [])

(comment
  (foo))
