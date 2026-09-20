(ns learn.snippet.performFn-3
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (let [^{:var :i32} result start-value]
    (set! _ (ak/& result))
    result))
