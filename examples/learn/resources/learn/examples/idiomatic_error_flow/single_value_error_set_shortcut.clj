(ns learn.examples.idiomatic-error-flow.single-value-error-set-shortcut
  (:require [aguafria.zig :as az]))

;; error.FileNotFound is shorthand for the singleton set's member.
(az/defconst failure (az/error-value :FileNotFound))
