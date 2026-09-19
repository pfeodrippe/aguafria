(ns learn.examples.idiomatic-comptime.export-any-symbol-name
  "Converted from export_any_symbol_name.zig"
  (:require [aguafria.zig :as az]))

(az/defn sentence-function :void
  {:attrs #{:export}
   :zig/name "@\"A function name that is a complete sentence.\""}
  [])
