(ns learn.snippet.performFn-3
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (let [result (ak/var start-value :i32)]
    (ak/= :_ (ak/& result))
    result))
