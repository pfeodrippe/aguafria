(ns learn.example.struct-default-value
  (:require [aguafria.zig :as az]))

(az/defconst Threshold
  (az/container {:kind :struct}
    (az/field-decl :minimum :f32)
    (az/field-decl :maximum :f32)
    ;; A namespaced default value is an ordinary instance of the enclosing type.
    (az/const-decl default Threshold {:minimum 0.25 :maximum 0.75})))
