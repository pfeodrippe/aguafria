(ns learn.snippet.performFn-1
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn- perform-fn :i32 [[start-value :i32]]
  (let [after-two (two start-value)]
    (three after-two)))
