(ns learn.snippet.handle-no-error-scenarios
  (:require [aguafria.zig :as az]))

(az/defn- do-a-different-thing :void [[text [:slice :u8]]]
  (az/if-capture-stmt {:payload [number] :error [_]}
    (parse-u64 text 10)
    (do-something-with-number number)
    ;; The caller chooses to ignore the error.
    (az/block)))
