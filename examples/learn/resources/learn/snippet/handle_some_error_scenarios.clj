(ns learn.snippet.handle-some-error-scenarios
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- do-another-thing [:error-union [:error-set [:InvalidChar]] :void]
  [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [error]}
                     (parse-u64 text 10)
                     (do-something-with-number number)
                     (k/switch error
                       ;; Handle overflow here.
                       (a/error-value :Overflow) (a/block)
                       (a/with-block :_ [remaining-error] (k/return remaining-error)))))
