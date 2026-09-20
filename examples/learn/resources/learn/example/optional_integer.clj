(ns learn.example.optional-integer
  (:require [aguafria.zig :as az]))

;; Normal integer.
(az/defconst normal-int :i32 1234)

;; Optional integer.
(az/defconst optional-int [:optional :i32] 5678)

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
