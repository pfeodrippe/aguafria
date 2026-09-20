(ns learn.example.tldoc-comments
  "This module provides functions for retrieving the current date and
  time with varying degrees of precision and accuracy. It does not
  depend on libc, but will use functions from it if available."
  (:require [aguafria.zig :as az]))

(az/defstruct S
  ;; Zig also permits top-level documentation inside non-module containers.
  ;; Its package documentation currently ignores those comments.
  [])

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
