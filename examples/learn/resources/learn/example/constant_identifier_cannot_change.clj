(ns learn.example.constant-identifier-cannot-change
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst x 1234)

(az/defn- change-constant :void
  []
  ;; It works at file scope as well as inside functions.
  (let [y 5678]
    ;; Once assigned, an identifier cannot be changed.
    (ak/+= y 1)))

(az/defn main :void
  []
  (change-constant))

(comment
  (main))
