(ns learn.snippet.performFn-3
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- perform-fn :i32 [[start-value :i32]]
  (let [result (k/var start-value :i32)]
    (k/= :_ (k/& result))
    result))
