(ns learn.example.compile-variables
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target.Os :as os]
            [aguafria.zig :as az]))

(az/defconst separator
  (if (k/== (os/-tag builtin/os) :.windows)
    \\
    \/))
