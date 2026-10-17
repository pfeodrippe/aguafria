(ns learn.snippet.cmpxchgWeakButNotAtomic
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- weak-compare-exchange [:optional T]
  [[T {:attrs #{comptime}} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (when (and (== previous expected) (usually-true-but-sometimes-false))
      (= @pointer replacement)
      (return nil))
    previous))
