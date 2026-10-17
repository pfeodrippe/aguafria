(ns learn.example.test-string-literal-to-const-slice
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn foo :void
  [[s [:slice-const :u8]]]
  (= :_ s))

(a/deftest string-literal-to-constant-slice
  (foo "hello"))

(comment
  (string-literal-to-constant-slice))
