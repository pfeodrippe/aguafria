(ns learn.example.test-string-literal-to-slice
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[s [:slice :u8]]]
  (k/= :_ s))

(a/deftest string-literal-to-mutable-slice
  (foo "hello"))

(comment
  (string-literal-to-mutable-slice))
