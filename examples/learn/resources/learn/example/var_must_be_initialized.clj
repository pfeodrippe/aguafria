(ns learn.example.var-must-be-initialized
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [x]
    (= x 1)))

(comment
  (main))
