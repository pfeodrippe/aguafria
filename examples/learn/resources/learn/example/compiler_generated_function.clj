(ns learn.example.compiler-generated-function
  (:require [aguafria.zig :as az]))

(az/defn- maximum :bool [[left :bool] [right :bool]]
  (or left right))
