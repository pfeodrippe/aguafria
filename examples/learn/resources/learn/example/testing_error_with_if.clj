(ns learn.example.testing-error-with-if
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- get-number-or-fail :!i32
  []
  (a/error-value :UnableToReturnNumber))

(a/defn main :void
  []
  (let [result (get-number-or-fail)]
    (a/if-capture-stmt {:payload [number] :error [err]} result
                       (debug/print "got number: {}\n" [number])
                       (debug/print "got error: {s}\n" [(k/errorName err)]))))

(comment
  (main))
