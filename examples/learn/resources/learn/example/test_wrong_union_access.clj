(ns learn.example.test-wrong-union-access
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Payload
  (az/union
   [[:int :i64]
    [:float :f64]
    [:boolean :bool]]))

(az/deftest simple-union
  (let [payload (k/var (Payload {:int 1234}))]
    (k/= (:float payload) 12.34)))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (simple-union))
