(ns learn.example.test-comptime-reaching-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- assert :void [[ok :bool]]
  (when (k/! ok)
    (k/unreachable))) ; assertion failure

(az/defcomptime reject-false-condition
  (assert false))
