(ns learn.snippet.handle-some-error-scenarios
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- do-another-thing [:error-union [:error-set [:InvalidChar]] :void]
  [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [error]}
                      (parse-u64 text 10)
                      (do-something-with-number number)
                      (a/switch-stmt error
      ;; Handle overflow here.
                        (case [(a/error-value :Overflow)] (a/block))
                        (a/case-else [remaining-error] (k/return remaining-error)))))
