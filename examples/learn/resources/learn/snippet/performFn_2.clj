(ns learn.snippet.performFn-2
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn- perform-fn :i32 [[start-value :i32]]
  (one start-value))
