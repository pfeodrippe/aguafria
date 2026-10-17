(ns learn.example.compiler-generated-function
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn- max :bool [[a :bool] [b :bool]]
  (or a b))

(comment
  (max false true))
