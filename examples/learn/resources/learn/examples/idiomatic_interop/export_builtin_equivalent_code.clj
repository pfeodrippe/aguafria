(ns learn.examples.idiomatic-interop.export-builtin-equivalent-code
  (:require [aguafria.zig :as az]))

(az/defn foo :void
  {:attrs #{:export}}
  [])
