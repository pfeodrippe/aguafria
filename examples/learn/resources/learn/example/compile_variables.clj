(ns learn.example.compile-variables
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst builtin (ak/import "builtin"))

(az/defconst separator
  (if (== (az/field (az/field builtin :os) :tag) :.windows)
    \\
    \/))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
