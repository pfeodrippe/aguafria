(ns learn.example.var-must-be-initialized
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Intentionally invalid: a local binding needs an initial value.
;; Use ak/undefined explicitly if its initial contents are undefined.
(az/defn main :void
  []
  (let [x]
    (ak/= x 1)))

(comment
  (main))
