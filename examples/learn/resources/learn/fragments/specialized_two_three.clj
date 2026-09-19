(ns learn.fragments.specialized-two-three
  "Converted from performFn_1"
  (:require [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (let [after-two (two start-value)]
    (three after-two)))
