(ns learn.example.test-shadowing
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst pi 3.14)

(az/deftest shadowing-test
  ;; Let's even go inside another block.
  (let [pi (ak/var 1234 :i32)]))

(comment
  (shadowing-test))
