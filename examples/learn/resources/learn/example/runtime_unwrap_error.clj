(ns learn.example.runtime-unwrap-error
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- getNumberOrFail [:error-union :i32] []
  (a/error-value :UnableToReturnNumber))

(a/defn main :void []
  (let [number (k/catch (getNumberOrFail) (unreachable))]
    (debug/print "value: {}\n" [number])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
