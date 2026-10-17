(ns learn.example.test-wrong-union-access
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defunion Payload
  [[:int :i64]
   [:float :f64]
   [:boolean :bool]])

(a/deftest simple-union
  (let [payload (k/var (Payload {:int 1234}))]
    (= (:float payload) 12.34)))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (simple-union))
