(ns learn.example.test-scopes
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest separate-scopes
  (let [pi 3.14]
    (= :_ pi))
  (let [pi (k/var true :bool)]
    (= :_ (k/& pi))))

(comment
  (separate-scopes))
