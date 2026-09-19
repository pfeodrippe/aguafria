(ns learn.examples.idiomatic-pointers.string-literal-to-slice
  "Converted from test_string_literal_to_slice.zig"
  (:require [aguafria.zig :as az]))

(az/defn accept-mutable-string :void
  [[text [:slice :u8]]]
  (set! _ text))

(az/deftest string-to-mutable-slice-test
  ;; Intentionally invalid: a string literal cannot become mutable storage.
  (accept-mutable-string "hello"))
