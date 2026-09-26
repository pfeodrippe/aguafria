(ns learn.example.not-atomic-cmpxchgStrong
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn cmpxchgStrongButNotAtomic [:optional T]
  [[T {:attrs #{k/comptime}} :type]
   [ptr [:* T]] [expected-value T] [new-value T]]
  (let [old-value @ptr]
    (if (k/== old-value expected-value)
      (do
        (k/= @ptr new-value)
        nil)
      old-value)))
