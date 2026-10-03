(ns learn.example.test-comptime-call-extern-function
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defextern exit :noreturn
  [])

(a/deftest foo
  (k/comptime
   (a/block
    (exit))))

(comment
  (foo))
