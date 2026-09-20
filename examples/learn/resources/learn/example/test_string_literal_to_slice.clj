(ns learn.example.test-string-literal-to-slice
  (:require [aguafria.zig :as az]))

(az/defn accept-mutable-string :void
  [[text [:slice :u8]]]
  (set! _ text))

(az/deftest string-to-mutable-slice-test
  ;; Intentionally invalid: a string literal cannot become mutable storage.
  (accept-mutable-string "hello"))

(comment
  (string-to-mutable-slice-test))
