(ns learn.snippet.handle-all-error-scenarios
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- do-a-thing :void [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [error]}
                      (parse-u64 text 10)
                      (do-something-with-number number)
                      (a/switch-stmt error
      ;; Handle overflow here.
                        (case [(a/error-value :Overflow)] (a/block))
      ;; InvalidChar is promised impossible; safety checks trap if it occurs.
                        (case [(a/error-value :InvalidChar)] (k/unreachable)))))
