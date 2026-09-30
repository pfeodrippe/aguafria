(ns aguafria.zig.discovery-error-union-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Errors (az/type [:error-set [:First :Second]]))

(az/defn main :void
  []
  (k/= :_ (k/TypeOf (:Second Errors)))
  (let [number-or-error (-> (:First Errors)
                            (k/as [:error-union Errors :i32])
                            k/var)]
    (debug/print "type: {}, value: {!}\n"
                 [(k/TypeOf number-or-error) number-or-error])
    (k/= number-or-error 1234)
    (debug/print "after: {}, value: {!}\n"
                 [(k/TypeOf number-or-error) number-or-error])))
