(ns learn.example.compile-variables
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.std.Target.Os :as os]
            [aguafria.zig :as az]))

(az/defconst separator
  (if (ak/== (os/-tag builtin/os) :.windows)
    \\
    \/))
