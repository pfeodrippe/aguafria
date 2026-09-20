(ns learn.example.test-shadowing
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst pi 3.14)

(az/deftest shadowing-test
  ;; Let's even go inside another block.
  (let [^:var pi (ak/i32 1234)]))

(comment
  (shadowing-test))
