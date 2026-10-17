(ns learn.snippet.handle-some-error-scenarios
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- do-another-thing [:error-union [:error-set [:InvalidChar]] :void]
  [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [error]}
                     (parse-u64 text 10)
                     (do-something-with-number number)
                     (switch error
                       ;; Handle overflow here.
                       (a/error-value :Overflow) (a/block)
                       (a/with-block :_ [remaining-error] (return remaining-error)))))
