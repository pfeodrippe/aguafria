(ns learn.examples.idiomatic-pointers.string-literal-to-const-slice
  "Converted from test_string_literal_to_const_slice.zig"
  (:require [aguafria.zig :as az]))

(az/defn accept-string :void
  [[text [:slice-const :u8]]]
  (set! _ text))

(az/deftest string-to-const-slice-test
  (accept-string "hello"))
