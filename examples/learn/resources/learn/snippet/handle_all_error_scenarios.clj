(ns learn.snippet.handle-all-error-scenarios
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- do-a-thing :void [[text [:slice :u8]]]
  (az/if-capture-stmt {:payload [number] :error [error]}
                      (parse-u64 text 10)
                      (do-something-with-number number)
                      (az/switch-stmt error
      ;; Handle overflow here.
                        (case [(az/error-value :Overflow)] (az/block))
      ;; InvalidChar is promised impossible; safety checks trap if it occurs.
                        (case [(az/error-value :InvalidChar)] (ak/unreachable)))))
