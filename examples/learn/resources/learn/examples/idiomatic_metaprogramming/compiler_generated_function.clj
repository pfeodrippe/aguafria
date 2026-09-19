(ns learn.examples.idiomatic-metaprogramming.compiler-generated-function
  "Converted from compiler_generated_function.zig"
  (:require [aguafria.zig :as az]))

(az/defn- maximum :bool [[left :bool] [right :bool]]
  (or left right))
