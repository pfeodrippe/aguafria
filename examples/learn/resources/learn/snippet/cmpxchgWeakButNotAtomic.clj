(ns learn.snippet.cmpxchgWeakButNotAtomic
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- weak-compare-exchange [:optional T]
  [[T {:attrs #{ak/comptime}} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (when (and (ak/== previous expected) (usually-true-but-sometimes-false))
      (ak/= @pointer replacement)
      (ak/return nil))
    previous))
