(ns learn.example.test-scopes
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest separate-scopes-test
  (let [pi 3.14]
    (set! _ pi))
  (let [^:var pi (ak/bool true)]
    (set! _ (& pi))))

(comment
  (separate-scopes-test))
