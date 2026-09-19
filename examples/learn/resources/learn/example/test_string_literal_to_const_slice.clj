(ns learn.example.test-string-literal-to-const-slice
  (:require [aguafria.zig :as az]))

(az/defn accept-string :void
  [[text [:slice-const :u8]]]
  (set! _ text))

(az/deftest string-to-const-slice-test
  (accept-string "hello"))
