(ns learn.example.test-shadowing
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst pi 3.14)

(az/deftest shadowing-test
  ;; Let's even go inside another block.
  (let [pi (k/var 1234 :i32)]))

(comment
  (shadowing-test))
