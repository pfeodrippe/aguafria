(ns learn.snippet.performFn-3
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- perform-fn :i32 [[start-value :i32]]
  (let [result (k/var start-value :i32)]
    (= :_ (k/& result))
    result))
