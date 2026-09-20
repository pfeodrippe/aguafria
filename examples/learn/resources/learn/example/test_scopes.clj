(ns learn.example.test-scopes
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest separate-scopes-test
  (let [pi 3.14]
    (ak/= :_ pi))
  (let [pi (ak/var true :bool)]
    (ak/= :_ (& pi))))

(comment
  (separate-scopes-test))
