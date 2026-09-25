(ns learn.example.export-any-symbol-name
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn sentence-function :void
  {:attrs #{ak/export}
   :zig/name "@\"A function name that is a complete sentence.\""}
  [])

(comment
  (sentence-function))
