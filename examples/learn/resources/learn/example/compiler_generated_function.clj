(ns learn.example.compiler-generated-function
  (:require [aguafria.zig :as az]))

(az/defn- max :bool [[a :bool] [b :bool]]
  (or a b))

(comment
  (max false true))
