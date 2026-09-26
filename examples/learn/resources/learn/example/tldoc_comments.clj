(ns learn.example.tldoc-comments
  "This module provides functions for retrieving the current date and
  time with varying degrees of precision and accuracy. It does not
  depend on libc, but will use functions from it if available."
  (:require [aguafria.zig :as az]))

(az/defstruct S
  ;; Top level comments are allowed inside a container other than a module,
  ;; but it is not very useful.  Currently, when producing the package
  ;; documentation, these comments are ignored.
  [])
