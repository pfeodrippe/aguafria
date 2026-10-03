(ns learn.example.optional-integer
  (:require [aguafria.zig :as a]))

;; normal integer
(a/defconst normal-int :i32 1234)

;; optional integer
(a/defconst optional-int [:optional :i32] 5678)
