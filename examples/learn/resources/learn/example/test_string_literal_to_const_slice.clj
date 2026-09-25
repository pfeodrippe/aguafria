(ns learn.example.test-string-literal-to-const-slice
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[text [:slice-const :u8]]]
  (k/= :_ text))

(az/deftest string-to-const-slice-test
  (foo "hello"))

(comment
  (string-to-const-slice-test))
