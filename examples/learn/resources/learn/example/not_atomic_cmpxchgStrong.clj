(ns learn.example.not-atomic-cmpxchgStrong
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; This describes compare/exchange's result, but provides no atomicity.
(az/defn cmpxchgStrongButNotAtomic [:optional T]
  [[T {:attrs #{k/comptime}} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (if (k/== previous expected)
      (do
        (k/= @pointer replacement)
        nil)
      previous)))
