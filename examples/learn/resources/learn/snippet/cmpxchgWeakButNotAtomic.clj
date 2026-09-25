(ns learn.snippet.cmpxchgWeakButNotAtomic
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- weak-compare-exchange [:optional T]
  [[T {:attrs #{k/comptime}} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (when (and (k/== previous expected) (usually-true-but-sometimes-false))
      (k/= @pointer replacement)
      (k/return nil))
    previous))
