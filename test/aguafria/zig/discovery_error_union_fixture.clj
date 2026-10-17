(ns aguafria.zig.discovery-error-union-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defconst Errors (a/type [:error-set [:First :Second]]))

(a/defn main :void
  []
  (= :_ (TypeOf (:Second Errors)))
  (let [number-or-error (-> (:First Errors)
                            (as [:error-union Errors :i32])
                            k/var)]
    (debug/print "type: {}, value: {!}\n"
                 [(TypeOf number-or-error) number-or-error])
    (= number-or-error 1234)
    (debug/print "after: {}, value: {!}\n"
                 [(TypeOf number-or-error) number-or-error])))
