(ns learn.snippet.performFn-3
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- perform-fn :i32 [[start-value :i32]]
  (let [result (k/var start-value :i32)]
    (k/= :_ (k/& result))
    result))
