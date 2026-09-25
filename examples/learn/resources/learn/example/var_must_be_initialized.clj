(ns learn.example.var-must-be-initialized
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; Intentionally invalid: a local binding needs an initial value.
;; Use k/undefined explicitly if its initial contents are undefined.
(az/defn main :void
  []
  (let [x]
    (k/= x 1)))

(comment
  (main))
