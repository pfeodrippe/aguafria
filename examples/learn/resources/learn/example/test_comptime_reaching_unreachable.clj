(ns learn.example.test-comptime-reaching-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- assert :void [[ok :bool]]
  (when (k/! ok)
    (k/unreachable))) ; assertion failure

(a/defcomptime reject-false-condition
  (assert false))
