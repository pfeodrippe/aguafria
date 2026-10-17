(ns learn.example.test-comptime-reaching-unreachable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- assert :void [[ok :bool]]
  (when (! ok)
    (unreachable))) ; assertion failure

(a/defcomptime reject-false-condition
  (assert false))
