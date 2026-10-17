(ns learn.example.compile-variables
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.Target.Os :as os]
            [aguafria.zig :as a]))

(a/defconst separator
  (if (== (os/-tag builtin/os) :windows)
    \\
    \/))
