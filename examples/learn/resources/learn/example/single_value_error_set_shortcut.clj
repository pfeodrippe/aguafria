(ns learn.example.single-value-error-set-shortcut
  (:require [aguafria.zig :as az]))

;; error.FileNotFound is shorthand for the singleton set's member.
(az/defconst failure (az/error-value :FileNotFound))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
