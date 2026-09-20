(ns learn.example.compile-variables
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst separator
  (if (ak/== (az/field builtin/os :tag) :.windows)
    \\
    \/))
