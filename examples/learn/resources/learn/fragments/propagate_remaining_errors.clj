(ns learn.fragments.propagate-remaining-errors
  "Converted from handle_some_error_scenarios.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- do-another-thing [:error-union [:error-set [:InvalidChar]] :void]
  [[text [:slice :u8]]]
  (az/if-capture-stmt {:payload [number] :error [error]}
    (parse-u64 text 10)
    (do-something-with-number number)
    (az/switch-stmt error
      ;; Handle overflow here.
      (case [(az/error-value :Overflow)] (az/block))
      (az/case-else [remaining-error] (ak/return remaining-error)))))
