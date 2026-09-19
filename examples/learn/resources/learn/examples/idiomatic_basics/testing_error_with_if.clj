(ns learn.examples.idiomatic-basics.testing-error-with-if
  "Converted from testing_error_with_if.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [result (get-number-or-fail)]
    (az/if-capture-stmt {:payload [number] :error [error]} result
      (debug/print "got number: {}\n" [number])
      (debug/print "got error: {s}\n" [(ak/errorName error)]))))

(az/defn- get-number-or-fail :i32
  {:zig/qualifiers "!"}
  []
  (az/error-value :UnableToReturnNumber))
