(ns learn.example.export-builtin-equivalent-code
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  {:attrs #{k/export}}
  [])

(comment
  (foo))
