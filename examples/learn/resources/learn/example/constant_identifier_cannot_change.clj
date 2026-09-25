(ns learn.example.constant-identifier-cannot-change
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst x 1234)

(az/defn- foo :void
  []
  ;; It works at file scope as well as inside functions.
  (let [y 5678]
    ;; Once assigned, an identifier cannot be changed.
    (k/+= y 1)))

(az/defn main :void
  []
  (foo))

(comment
  (main))
