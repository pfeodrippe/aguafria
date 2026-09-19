(ns learn.examples.idiomatic-basics.scopes
  "Converted from test_scopes.zig"
  (:require [aguafria.zig :as az]))

(az/deftest separate-scopes-test
  (let [pi 3.14]
    (set! _ pi))
  (let [^{:var :bool} pi true]
    (set! _ (& pi))))
