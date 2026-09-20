(ns learn.example.compiler-generated-function
  (:require [aguafria.zig :as az]))

(az/defn- max :bool [[left :bool] [right :bool]]
  (or left right))

(comment
  (max false true))
