(ns learn.example.single-value-error-set-shortcut
  (:require [aguafria.zig :as a]))

(a/defconst err (a/error-value :FileNotFound))
