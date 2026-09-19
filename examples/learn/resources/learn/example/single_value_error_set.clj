(ns learn.example.single-value-error-set
  (:require [aguafria.zig :as az]))

;; Select the member from an explicitly constructed single-value error set.
(az/defconst failure
  (az/field (az/type [:error-set [:FileNotFound]]) :FileNotFound))
