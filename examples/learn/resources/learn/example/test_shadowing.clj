(ns learn.example.test-shadowing
  (:require [aguafria.zig :as az]))

(az/defconst pi 3.14)

(az/deftest shadowing-test
  ;; Let's even go inside another block.
  (let [^{:var :i32} pi 1234]))

(comment
  (shadowing-test))
