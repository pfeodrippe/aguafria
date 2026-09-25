(ns learn.example.not-atomic-cmpxchgStrong
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; This describes compare/exchange's result, but provides no atomicity.
(az/defn cmpxchgStrongButNotAtomic [:optional T]
  [[T {:attrs #{ak/comptime}} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (if (ak/== previous expected)
      (do
        (ak/= @pointer replacement)
        nil)
      previous)))
