(ns learn.snippet.performFn-2
  (:require [aguafria.zig :as a]))

(a/defn- perform-fn :i32 [[start-value :i32]]
  (one start-value))
