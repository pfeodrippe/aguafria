(ns learn.example.optional-integer
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

;; normal integer
(a/defconst normal-int :i32 1234)

;; optional integer
(a/defconst optional-int [:optional :i32] 5678)
