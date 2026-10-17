(ns learn.example.test-comptime-call-extern-function
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defextern exit :noreturn
  [])

(a/deftest foo
  (comptime
   (a/block
    (exit))))

(comment
  (foo))
