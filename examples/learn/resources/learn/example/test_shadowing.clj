(ns learn.example.test-shadowing
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst pi 3.14)

(a/deftest inside-test-block
  ;; Let's even go inside another block
  (let [pi (k/var 1234 :i32)]))

(comment
  (inside-test-block))
