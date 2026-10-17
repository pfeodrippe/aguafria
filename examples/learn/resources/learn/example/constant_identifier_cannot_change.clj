(ns learn.example.constant-identifier-cannot-change
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst x 1234)

(a/defn- foo :void
  []
  ;; It works at file scope as well as inside functions.
  (let [y 5678]
    ;; Once assigned, an identifier cannot be changed.
    (+= y 1)))

(a/defn main :void
  []
  (foo))

(comment
  (main))
