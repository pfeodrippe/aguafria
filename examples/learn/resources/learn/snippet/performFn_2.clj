(ns learn.snippet.performFn-2
  (:require [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (one start-value))
