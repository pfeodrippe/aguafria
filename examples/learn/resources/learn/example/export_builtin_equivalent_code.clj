(ns learn.example.export-builtin-equivalent-code
  (:require [aguafria.zig :as az]))

(az/defn foo :void
  {:attrs #{:export}}
  [])

(comment
  (foo))
