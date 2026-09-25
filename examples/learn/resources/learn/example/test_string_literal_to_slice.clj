(ns learn.example.test-string-literal-to-slice
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[text [:slice :u8]]]
  (k/= :_ text))

(az/deftest string-to-mutable-slice-test
  ;; Intentionally invalid: a string literal cannot become mutable storage.
  (foo "hello"))

(comment
  (string-to-mutable-slice-test))
