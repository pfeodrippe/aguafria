(ns learn.example.test-scopes
  (:require [aguafria.zig :as az]))

(az/deftest separate-scopes-test
  (let [pi 3.14]
    (set! _ pi))
  (let [^{:var :bool} pi true]
    (set! _ (& pi))))
