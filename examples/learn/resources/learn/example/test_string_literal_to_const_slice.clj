(ns learn.example.test-string-literal-to-const-slice
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[s [:slice-const :u8]]]
  (k/= :_ s))

(a/deftest string-literal-to-constant-slice
  (foo "hello"))

(comment
  (string-literal-to-constant-slice))
