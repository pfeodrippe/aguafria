(ns learn.example.optional-integer
  (:require [aguafria.zig :as az]))

;; normal integer
(az/defconst normal-int :i32 1234)

;; optional integer
(az/defconst optional-int [:optional :i32] 5678)
