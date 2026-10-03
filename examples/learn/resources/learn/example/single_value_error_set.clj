(ns learn.example.single-value-error-set
  (:require [aguafria.zig :as a]))

(a/defconst err
  (:FileNotFound (a/type [:error-set [:FileNotFound]])))
