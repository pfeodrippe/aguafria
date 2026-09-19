(ns learn.examples.idiomatic-low-level.non-atomic-compare-exchange
  (:require [aguafria.zig :as az]))

;; This describes compare/exchange's result, but provides no atomicity.
(az/defn compare-exchange-not-atomic [:optional T]
  [[T {:zig/prefix "comptime"} :type]
   [pointer [:* T]] [expected T] [replacement T]]
  (let [previous @pointer]
    (if (== previous expected)
      (do
        (set! @pointer replacement)
        nil)
      previous)))
