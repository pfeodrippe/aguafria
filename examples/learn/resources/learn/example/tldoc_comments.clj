(ns learn.example.tldoc-comments
  "Provides functions for retrieving the current date and time with varying\n  degrees of precision and accuracy."
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct S
  ;; Top level comments are allowed inside namespaces other than the
  ;; implicit struct created by files, but it is not very useful. Currently,
  ;; when producing the package documentation, these comments are ignored.
  [])
