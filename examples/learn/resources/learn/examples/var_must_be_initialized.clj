(ns learn.examples.var-must-be-initialized
  "Converted from var_must_be_initialized.zig"
  (:require [aguafria.zig :as az]))

;; Intentionally invalid: a local binding needs an initial value.
;; Use ak/undefined explicitly if its initial contents are undefined.
(az/defn main :void
  []
  (let [^{:var :i32} x]
    (set! x 1)))
