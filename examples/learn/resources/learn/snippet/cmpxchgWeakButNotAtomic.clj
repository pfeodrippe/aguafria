(ns learn.snippet.cmpxchgWeakButNotAtomic
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- weak-compare-exchange [:optional T]
  [[T {:zig/prefix "comptime"} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (when (and (== previous expected) (usually-true-but-sometimes-false))
      (set! @pointer replacement)
      (ak/return nil))
    previous))
