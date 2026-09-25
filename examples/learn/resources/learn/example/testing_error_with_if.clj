(ns learn.example.testing-error-with-if
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- get-number-or-fail :!i32
  []
  (az/error-value :UnableToReturnNumber))

(az/defn main :void
  []
  (let [result (get-number-or-fail)]
    (az/if-capture-stmt {:payload [number] :error [error]} result
                        (debug/print "got number: {}\n" [number])
                        (debug/print "got error: {s}\n" [(k/errorName error)]))))

(comment
  (main))
