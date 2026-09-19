(ns learn.examples.idiomatic-error-flow.single-value-error-set-shortcut
  "Converted from single_value_error_set_shortcut.zig"
  (:require [aguafria.zig :as az]))

;; error.FileNotFound is shorthand for the singleton set's member.
(az/defconst failure (az/error-value :FileNotFound))
