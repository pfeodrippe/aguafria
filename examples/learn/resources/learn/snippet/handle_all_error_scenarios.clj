(ns learn.snippet.handle-all-error-scenarios
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- do-a-thing :void [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [error]}
                     (parse-u64 text 10)
                     (do-something-with-number number)
                     (switch error
                       ;; Handle overflow here.
                       (a/error-value :Overflow) (a/block)
                       ;; InvalidChar is promised impossible; safety checks trap if it occurs.
                       (a/error-value :InvalidChar) (unreachable))))
