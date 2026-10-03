(ns learn.snippet.handle-no-error-scenarios
  (:require [aguafria.zig :as a]))

(a/defn- do-a-different-thing :void [[text [:slice :u8]]]
  (a/if-capture-stmt {:payload [number] :error [_]}
                      (parse-u64 text 10)
                      (do-something-with-number number)
    ;; The caller chooses to ignore the error.
                      (a/block)))
