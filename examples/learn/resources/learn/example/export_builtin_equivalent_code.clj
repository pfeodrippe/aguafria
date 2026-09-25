(ns learn.example.export-builtin-equivalent-code
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :void
  {:attrs #{ak/export}}
  [])

(comment
  (foo))
