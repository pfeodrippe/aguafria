(ns learn.example.test-string-literal-to-const-slice
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[text [:slice-const :u8]]]
  (ak/= :_ text))

(az/deftest string-to-const-slice-test
  (foo "hello"))

(comment
  (string-to-const-slice-test))
