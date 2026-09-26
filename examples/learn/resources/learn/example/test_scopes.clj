(ns learn.example.test-scopes
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest separate-scopes
  (let [pi 3.14]
    (k/= :_ pi))
  (let [pi (k/var true :bool)]
    (k/= :_ (k/& pi))))

(comment
  (separate-scopes))
