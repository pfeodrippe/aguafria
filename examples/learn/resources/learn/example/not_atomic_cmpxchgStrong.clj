(ns learn.example.not-atomic-cmpxchgStrong
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn cmpxchgStrongButNotAtomic [:optional T]
  [[T {:attrs #{comptime}} :type]
   [ptr [:* T]] [expected-value T] [new-value T]]
  (let [old-value @ptr]
    (if (== old-value expected-value)
      (do
        (= @ptr new-value)
        nil)
      old-value)))
