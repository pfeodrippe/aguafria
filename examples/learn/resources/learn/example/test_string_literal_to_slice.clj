(ns learn.example.test-string-literal-to-slice
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[s [:slice :u8]]]
  (= :_ s))

(a/deftest string-literal-to-mutable-slice
  (foo "hello"))

(comment
  (string-literal-to-mutable-slice))
