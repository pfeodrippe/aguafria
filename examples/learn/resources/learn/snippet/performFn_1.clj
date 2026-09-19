(ns learn.snippet.performFn-1
  (:require [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (let [after-two (two start-value)]
    (three after-two)))
